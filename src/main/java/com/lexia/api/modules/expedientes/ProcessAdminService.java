package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.TenantConfigDtos.ProcessConfig;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessAdminService {

  private static final Set<String> STAGE_COLOR_KEYS =
      Set.of("blue", "green", "purple", "orange", "red", "gray");
  private static final Set<String> GATE_RESPONSE_TYPES = Set.of("yes_no", "multiple");
  private static final Set<String> GATE_CONTINUE_CRITERIA =
      Set.of("affirmative", "negative");

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final GateDefRepository gateDefs;
  private final ValidationDefRepository validationDefs;
  private final TenantConfigService tenantConfigService;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeService configChanges;

  public ProcessAdminService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      GateDefRepository gateDefs,
      ValidationDefRepository validationDefs,
      TenantConfigService tenantConfigService,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeService configChanges) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.gateDefs = gateDefs;
    this.validationDefs = validationDefs;
    this.tenantConfigService = tenantConfigService;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public ProcessConfig getDefinition(String caseType) {
    return tenantConfigService.getProcessConfigForAdmin(caseType);
  }

  private static final Pattern NUMERIC_SUFFIX = Pattern.compile("(\\d+)\\s*$");

  @Transactional
  public ProcessConfig createGate(String caseType, ProcessAdminDtos.CreateGateRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    List<GateDef> existing =
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId);
    String code = nextCode("G", existing.stream().map(GateDef::getCode).toList());
    int sortOrder =
        existing.stream().map(GateDef::getSortOrder).max(Comparator.naturalOrder()).orElse(0) + 1;
    GateDef gate =
        GateDef.createNew(
            tenantId, process.getId(), code, request.question().trim(), sortOrder);
    gateDefs.save(gate);
    touchDraft(process, tenantId, caseType);
    return tenantConfigService.getProcessConfigForAdmin(caseType.trim().toUpperCase(Locale.ROOT));
  }

  @Transactional
  public ProcessConfig createValidation(
      String caseType, ProcessAdminDtos.CreateValidationRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    List<ValidationDef> existing =
        validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            process.getId(), tenantId);
    String code = nextCode("V", existing.stream().map(ValidationDef::getCode).toList());
    int sortOrder =
        existing.stream().map(ValidationDef::getSortOrder).max(Comparator.naturalOrder()).orElse(0)
            + 1;
    ValidationDef row =
        ValidationDef.createNew(
            tenantId, process.getId(), code, request.label().trim(), sortOrder);
    validationDefs.save(row);
    touchDraft(process, tenantId, caseType);
    return tenantConfigService.getProcessConfigForAdmin(caseType.trim().toUpperCase(Locale.ROOT));
  }

  @Transactional
  public ProcessConfig updateDefinition(String caseType, ProcessAdminDtos.UpdateProcessDefinitionRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    String normalizedCaseType = caseType.trim().toUpperCase();
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, normalizedCaseType)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND,
                        "PROCESS_NOT_FOUND",
                        "No hay definición de proceso para " + normalizedCaseType + "."));

    if (request.stages() != null) {
      for (ProcessAdminDtos.UpdateStageRequest stageUpdate : request.stages()) {
        ProcessStageDef stage =
            stageDefs
                .findByIdAndTenantId(stageUpdate.id(), tenantId)
                .orElseThrow(
                    () ->
                        new AuthException(
                            HttpStatus.NOT_FOUND, "STAGE_NOT_FOUND", "Etapa no encontrada."));
        if (!process.getId().equals(stage.getProcessDefinitionId())) {
          throw new AuthException(
              HttpStatus.BAD_REQUEST, "STAGE_SCOPE", "La etapa no pertenece a este proceso.");
        }
        if (stageUpdate.label() != null && !stageUpdate.label().isBlank()) {
          stage.setLabel(stageUpdate.label().trim());
        }
        if (stageUpdate.shortLabel() != null && !stageUpdate.shortLabel().isBlank()) {
          stage.setShortLabel(stageUpdate.shortLabel().trim());
        }
        if (stageUpdate.slaHours() != null) {
          stage.setSlaHours(stageUpdate.slaHours());
        }
        if (stageUpdate.colorKey() != null && !stageUpdate.colorKey().isBlank()) {
          String colorKey = stageUpdate.colorKey().trim().toLowerCase(Locale.ROOT);
          if (STAGE_COLOR_KEYS.contains(colorKey)) {
            stage.setColorKey(colorKey);
          }
        }
        stageDefs.save(stage);
      }
    }

    if (request.gates() != null) {
      for (ProcessAdminDtos.UpdateGateRequest gateUpdate : request.gates()) {
        GateDef gate =
            gateDefs
                .findByIdAndTenantId(gateUpdate.id(), tenantId)
                .orElseThrow(
                    () ->
                        new AuthException(
                            HttpStatus.NOT_FOUND, "GATE_NOT_FOUND", "Gate no encontrado."));
        if (!process.getId().equals(gate.getProcessDefinitionId())) {
          throw new AuthException(
              HttpStatus.BAD_REQUEST, "GATE_SCOPE", "El gate no pertenece a este proceso.");
        }
        if (gateUpdate.question() != null && !gateUpdate.question().isBlank()) {
          gate.setQuestion(gateUpdate.question().trim());
        }
        if (gateUpdate.responseType() != null && !gateUpdate.responseType().isBlank()) {
          String type = gateUpdate.responseType().trim().toLowerCase(Locale.ROOT);
          if (GATE_RESPONSE_TYPES.contains(type)) {
            gate.setResponseType(type);
          }
        }
        if (gateUpdate.continueCriterion() != null && !gateUpdate.continueCriterion().isBlank()) {
          String criterion = gateUpdate.continueCriterion().trim().toLowerCase(Locale.ROOT);
          if (GATE_CONTINUE_CRITERIA.contains(criterion)) {
            gate.setContinueCriterion(criterion);
          }
        }
        if (gateUpdate.mandatory() != null) {
          gate.setMandatory(gateUpdate.mandatory());
        }
        if (gateUpdate.messageOk() != null) {
          gate.setMessageOk(gateUpdate.messageOk().trim());
        }
        if (gateUpdate.messageFail() != null) {
          gate.setMessageFail(gateUpdate.messageFail().trim());
        }
        if (gateUpdate.active() != null) {
          gate.setActive(gateUpdate.active());
        }
        gateDefs.save(gate);
      }
    }

    if (request.validations() != null) {
      for (ProcessAdminDtos.UpdateValidationRequest validationUpdate : request.validations()) {
        ValidationDef validation =
            validationDefs
                .findByIdAndTenantId(validationUpdate.id(), tenantId)
                .orElseThrow(
                    () ->
                        new AuthException(
                            HttpStatus.NOT_FOUND,
                            "VALIDATION_NOT_FOUND",
                            "Validación no encontrada."));
        if (!process.getId().equals(validation.getProcessDefinitionId())) {
          throw new AuthException(
              HttpStatus.BAD_REQUEST,
              "VALIDATION_SCOPE",
              "La validación no pertenece a este proceso.");
        }
        if (validationUpdate.label() != null && !validationUpdate.label().isBlank()) {
          validation.setLabel(validationUpdate.label().trim());
        }
        if (validationUpdate.description() != null) {
          validation.setDescription(validationUpdate.description().trim());
        }
        if (validationUpdate.active() != null) {
          validation.setActive(validationUpdate.active());
        }
        validationDefs.save(validation);
      }
    }

    touchDraft(process, tenantId, normalizedCaseType);
    return tenantConfigService.getProcessConfigForAdmin(normalizedCaseType);
  }

  private ProcessDefinition requireProcess(UUID tenantId, String caseType) {
    String normalizedCaseType = caseType.trim().toUpperCase(Locale.ROOT);
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, normalizedCaseType)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND,
                    "PROCESS_NOT_FOUND",
                    "No hay definición de proceso para " + normalizedCaseType + "."));
  }

  private void touchDraft(ProcessDefinition process, UUID tenantId, String caseType) {
    String normalizedCaseType = caseType.trim().toUpperCase(Locale.ROOT);
    UUID userId = AuthContext.require().userId();
    process.touchDraft(userId, Instant.now());
    processDefinitions.save(process);
    configChanges.markDraftByCaseType(tenantId, normalizedCaseType);
    audit("admin.process.updated", process.getId(), normalizedCaseType);
  }

  private static String nextCode(String prefix, List<String> existingCodes) {
    int max = 0;
    for (String code : existingCodes) {
      if (code == null || !code.toUpperCase(Locale.ROOT).startsWith(prefix)) {
        continue;
      }
      Matcher matcher = NUMERIC_SUFFIX.matcher(code);
      if (matcher.find()) {
        max = Math.max(max, Integer.parseInt(matcher.group(1)));
      }
    }
    int next = max + 1;
    if ("G".equals(prefix)) {
      return prefix + "-" + String.format("%03d", next);
    }
    return prefix + next;
  }

  private void audit(String action, UUID processId, String caseType) {
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
            "OK",
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
