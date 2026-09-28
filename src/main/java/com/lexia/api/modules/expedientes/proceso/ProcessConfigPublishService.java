package com.lexia.api.modules.expedientes.proceso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ChangeSetState;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigDiffView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessPublicationHistoryItem;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessPublicationHistoryView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessPublicationState;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.PublishProcessConfigRequest;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.ProcessConfig;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.lexia.api.modules.expedientes.tenant.TenantConfigService;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigPublishService {

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final ProcessConfigPublicationRepository publications;
  private final TenantConfigService tenantConfigService;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeSetService changeSetService;
  private final ProcessConfigSnapshotService snapshotService;
  private final ProcessConfigDiffService diffService;
  private final ProcessConfigImpactService impactService;

  public ProcessConfigPublishService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      ProcessConfigPublicationRepository publications,
      TenantConfigService tenantConfigService,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeSetService changeSetService,
      ProcessConfigSnapshotService snapshotService,
      ProcessConfigDiffService diffService,
      @Lazy ProcessConfigImpactService impactService) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.publications = publications;
    this.tenantConfigService = tenantConfigService;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.changeSetService = changeSetService;
    this.snapshotService = snapshotService;
    this.diffService = diffService;
    this.impactService = impactService;
  }

  @Transactional(readOnly = true)
  public ProcessPublicationHistoryView history(String caseTypeParam) {
    authorization.requirePermission("admin:proceso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    List<ProcessPublicationHistoryItem> items =
        publications
            .findByTenantIdAndProcessDefinitionIdOrderByPublishedAtDesc(tenantId, process.getId())
            .stream()
            .map(
                row ->
                    new ProcessPublicationHistoryItem(
                        row.getId(),
                        row.getConfigVersion(),
                        row.getComment(),
                        row.getPublishedBy(),
                        row.getPublishedAt(),
                        row.hasSnapshot()))
            .toList();
    return new ProcessPublicationHistoryView(process.getCaseType(), process.getConfigVersion(), items);
  }

  @Transactional(readOnly = true)
  public ProcessConfigDiffView diffVersions(
      String caseTypeParam, int fromVersion, int toVersion) {
    authorization.requirePermission("admin:proceso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    ProcessConfigPublication from =
        requirePublication(tenantId, process.getId(), fromVersion);
    ProcessConfigPublication to = requirePublication(tenantId, process.getId(), toVersion);
    return diffService.diff(
        fromVersion, toVersion, from.getSnapshotJson(), to.getSnapshotJson());
  }

  @Transactional(readOnly = true)
  public ProcessConfigDiffView diffDraftAgainstActive(String caseTypeParam) {
    authorization.requirePermission("admin:proceso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    int activeVersion = process.getConfigVersion();
    ProcessConfigPublication active =
        publications
            .findByTenantIdAndProcessDefinitionIdAndConfigVersion(
                tenantId, process.getId(), activeVersion)
            .orElse(null);
    String draftJson = snapshotService.captureSnapshotJson(process.getCaseType());
    String activeJson = active != null ? active.getSnapshotJson() : null;
    ProcessConfigDiffView base =
        diffService.diff(activeVersion, activeVersion, activeJson, draftJson);
    String notice =
        "Borrador actual vs versión publicada v"
            + activeVersion
            + "."
            + (base.notice() != null ? " " + base.notice() : "");
    return new ProcessConfigDiffView(
        activeVersion, activeVersion, base.complete(), notice.trim(), base.changes());
  }

  @Transactional
  public ProcessConfig publish(String caseTypeParam, PublishProcessConfigRequest request) {
    authorization.requirePermission("admin:proceso:publicar");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);

    if (!process.isHasUnpublishedChanges()) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "NO_PENDING_CHANGES",
          "No hay cambios pendientes de publicación para este proceso.");
    }

    List<ProcessStageDef> stages =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            process.getId(), tenantId);
    if (stages.isEmpty()) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "PROCESS_INCOMPLETE",
          "El proceso debe tener al menos una etapa antes de publicar.");
    }

    ProcessConfigChangeSet approved =
        changeSetService.requireApproved(tenantId, process.getId());

    impactService.requireReadyForPromotion(process.getCaseType());

    String snapshotJson = snapshotService.captureSnapshotJson(process.getCaseType());
    int version = process.publish(userId);
    processDefinitions.save(process);
    ProcessConfigPublication publication =
        publications.save(
            ProcessConfigPublication.create(
                tenantId,
                process.getId(),
                version,
                request.comment().trim(),
                userId,
                snapshotJson));
    changeSetService.markPublished(approved, publication.getId());

    audit("admin.process.published", process.getId(), process.getCaseType(), version);
    return tenantConfigService.getProcessConfigForAdmin(process.getCaseType());
  }

  private ProcessConfigPublication requirePublication(
      UUID tenantId, UUID processId, int version) {
    return publications
        .findByTenantIdAndProcessDefinitionIdAndConfigVersion(tenantId, processId, version)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND,
                    "PUBLICATION_NOT_FOUND",
                    "No hay publicación v" + version + " para este proceso."));
  }

  private ProcessDefinition requireProcess(UUID tenantId, String caseTypeParam) {
    String caseType = caseTypeParam.trim().toUpperCase(Locale.ROOT);
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND,
                    "PROCESS_NOT_FOUND",
                    "No hay definición de proceso para " + caseType + "."));
  }

  private void audit(String action, UUID processId, String caseType, int version) {
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "process_definition",
            processId,
            "OK v" + version + " " + caseType,
            http != null ? http.getRemoteAddr() : null,
            http != null ? http.getHeader("User-Agent") : null));
  }

  private static HttpServletRequest currentRequest() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest();
    }
    return null;
  }
}
