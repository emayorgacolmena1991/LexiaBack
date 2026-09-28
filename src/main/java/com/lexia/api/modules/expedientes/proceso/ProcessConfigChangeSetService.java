package com.lexia.api.modules.expedientes.proceso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ChangeSetCommentRequest;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ChangeSetItemView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ChangeSetState;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigChangeSetService {

  private static final List<String> ACTIVE_STATUSES = List.of("DRAFT", "REVIEW", "APPROVED");

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessConfigChangeSetRepository changeSets;
  private final ProcessConfigChangeSetItemRepository changeSetItems;
  private final ProcessConfigImpactService impactService;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;

  public ProcessConfigChangeSetService(
      ProcessDefinitionRepository processDefinitions,
      ProcessConfigChangeSetRepository changeSets,
      ProcessConfigChangeSetItemRepository changeSetItems,
      @Lazy ProcessConfigImpactService impactService,
      AuthorizationService authorization,
      AuditEventRepository auditEvents) {
    this.processDefinitions = processDefinitions;
    this.changeSets = changeSets;
    this.changeSetItems = changeSetItems;
    this.impactService = impactService;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
  }

  @Transactional
  public void ensureOpenDraft(UUID tenantId, UUID processDefinitionId, String caseType) {
    UUID userId = AuthContext.require().userId();
    Optional<ProcessConfigChangeSet> active = findActiveChangeSet(tenantId, processDefinitionId);
    if (active.isPresent()) {
      return;
    }
    changeSets.save(
        ProcessConfigChangeSet.createDraft(tenantId, processDefinitionId, caseType, userId));
  }

  @Transactional
  public void recordItem(
      UUID tenantId,
      UUID processDefinitionId,
      String caseType,
      String domain,
      String summary,
      String entityRef) {
    if (domain == null || domain.isBlank() || summary == null || summary.isBlank()) {
      return;
    }
    String normalizedDomain = domain.trim().toUpperCase(Locale.ROOT);
    String rawSummary = summary.trim();
    final String itemSummary =
        rawSummary.length() > 500 ? rawSummary.substring(0, 497) + "…" : rawSummary;
    ProcessConfigChangeSet changeSet = resolveActiveDraft(tenantId, processDefinitionId, caseType);
    String ref = entityRef != null && !entityRef.isBlank() ? entityRef.trim() : null;
    changeSetItems
        .findByChangeSetIdAndDomain(changeSet.getId(), normalizedDomain)
        .ifPresentOrElse(
            existing -> {
              existing.refresh(itemSummary, ref);
              changeSetItems.save(existing);
            },
            () ->
                changeSetItems.save(
                    ProcessConfigChangeSetItem.create(
                        changeSet.getId(), tenantId, normalizedDomain, itemSummary, ref)));
  }

  @Transactional(readOnly = true)
  public ChangeSetState activeState(UUID tenantId, UUID processDefinitionId) {
    return findActiveChangeSet(tenantId, processDefinitionId)
        .map(this::toState)
        .orElse(null);
  }

  @Transactional
  public void submitForReview(String caseTypeParam, ChangeSetCommentRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    if (!process.isHasUnpublishedChanges()) {
      throw new AuthException(
          HttpStatus.CONFLICT, "NO_PENDING_CHANGES", "No hay cambios pendientes de revisión.");
    }
    impactService.requireReadyForPromotion(process.getCaseType());
    ProcessConfigChangeSet changeSet = resolveDraftForSubmit(tenantId, process, userId);
    changeSet.submitForReview(request.comment().trim(), userId);
    changeSets.save(changeSet);
    audit("admin.process.change_set.submitted", process.getId(), changeSet.getId());
  }

  @Transactional
  public void approve(String caseTypeParam, ChangeSetCommentRequest request) {
    authorization.requirePermission("admin:proceso:aprobar");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    ProcessConfigChangeSet changeSet = requireInReview(tenantId, process.getId());
    assertDifferentActor(changeSet.getSubmittedBy(), userId);
    changeSet.approve(request.comment().trim(), userId);
    changeSets.save(changeSet);
    audit("admin.process.change_set.approved", process.getId(), changeSet.getId());
  }

  @Transactional
  public void reject(String caseTypeParam, ChangeSetCommentRequest request) {
    authorization.requirePermission("admin:proceso:aprobar");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    ProcessConfigChangeSet changeSet = requireInReview(tenantId, process.getId());
    assertDifferentActor(changeSet.getSubmittedBy(), userId);
    changeSet.reject(request.comment().trim(), userId);
    changeSets.save(changeSet);
    audit("admin.process.change_set.rejected", process.getId(), changeSet.getId());
  }

  @Transactional
  public ProcessConfigChangeSet requireApproved(UUID tenantId, UUID processDefinitionId) {
    return changeSets
        .findFirstByTenantIdAndProcessDefinitionIdAndStatusInOrderByCreatedAtDesc(
            tenantId, processDefinitionId, List.of("APPROVED"))
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.CONFLICT,
                    "CHANGE_SET_NOT_APPROVED",
                    "Debe aprobar el ChangeSet antes de publicar."));
  }

  @Transactional
  public void markPublished(ProcessConfigChangeSet changeSet, UUID publicationId) {
    changeSet.markPublished(publicationId);
    changeSets.save(changeSet);
  }

  public ChangeSetState toState(ProcessConfigChangeSet row) {
    if (row == null) {
      return null;
    }
    List<ChangeSetItemView> items =
        changeSetItems.findByChangeSetIdOrderByDomainAsc(row.getId()).stream()
            .map(
                item ->
                    new ChangeSetItemView(
                        item.getDomain(),
                        item.getSummary(),
                        item.getEntityRef(),
                        item.getLastUpdatedAt()))
            .toList();
    return new ChangeSetState(
        row.getId(),
        row.getStatus(),
        row.getSubmitComment(),
        row.getSubmittedBy(),
        row.getSubmittedAt(),
        row.getReviewComment(),
        row.getReviewedBy(),
        row.getReviewedAt(),
        items);
  }

  private ProcessConfigChangeSet resolveActiveDraft(
      UUID tenantId, UUID processDefinitionId, String caseType) {
    UUID userId = AuthContext.require().userId();
    return findActiveChangeSet(tenantId, processDefinitionId)
        .orElseGet(
            () ->
                changeSets.save(
                    ProcessConfigChangeSet.createDraft(
                        tenantId, processDefinitionId, caseType, userId)));
  }

  private ProcessConfigChangeSet resolveDraftForSubmit(
      UUID tenantId, ProcessDefinition process, UUID userId) {
    Optional<ProcessConfigChangeSet> active = findActiveChangeSet(tenantId, process.getId());
    if (active.isPresent()) {
      ProcessConfigChangeSet changeSet = active.get();
      if ("REJECTED".equals(changeSet.getStatus())) {
        return changeSets.save(
            ProcessConfigChangeSet.createDraft(
                tenantId, process.getId(), process.getCaseType(), userId));
      }
      if ("DRAFT".equals(changeSet.getStatus())) {
        return changeSet;
      }
      throw new AuthException(
          HttpStatus.CONFLICT,
          "CHANGE_SET_NOT_SUBMITTABLE",
          "El ChangeSet activo no puede enviarse a revisión en su estado actual.");
    }
    return changeSets.save(
        ProcessConfigChangeSet.createDraft(
            tenantId, process.getId(), process.getCaseType(), userId));
  }

  private ProcessConfigChangeSet requireInReview(UUID tenantId, UUID processId) {
    return changeSets
        .findFirstByTenantIdAndProcessDefinitionIdAndStatusInOrderByCreatedAtDesc(
            tenantId, processId, List.of("REVIEW"))
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.CONFLICT,
                    "CHANGE_SET_NOT_IN_REVIEW",
                    "No hay un ChangeSet en revisión para este proceso."));
  }

  private Optional<ProcessConfigChangeSet> findActiveChangeSet(UUID tenantId, UUID processId) {
    return changeSets.findFirstByTenantIdAndProcessDefinitionIdAndStatusInOrderByCreatedAtDesc(
        tenantId, processId, ACTIVE_STATUSES);
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

  private static void assertDifferentActor(UUID submitterId, UUID actorId) {
    if (submitterId != null && submitterId.equals(actorId)) {
      throw new AuthException(
          HttpStatus.FORBIDDEN,
          "CHANGE_SET_SOD",
          "Segregación de funciones: quien envió a revisión no puede aprobar ni rechazar.");
    }
  }

  private void audit(String action, UUID processId, UUID changeSetId) {
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "process_config_change_set",
            changeSetId,
            "OK process=" + processId,
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
