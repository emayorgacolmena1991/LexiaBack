package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.ExpedienteDtos.StageAdvanceRequest;
import com.lexia.api.modules.expedientes.ExpedienteDtos.StageAdvanceResult;
import com.lexia.api.modules.expedientes.ExpedienteDtos.StageRevertRequest;
import com.lexia.api.modules.expedientes.ExpedienteDtos.StageRevertResult;
import com.lexia.api.modules.expedientes.WorkspaceDtos.StageTransitionOptions;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseStageTransitionService {

  private final LegalCaseRepository legalCases;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final ProcessStageTransitionRepository stageTransitions;
  private final CaseValidationRepository caseValidations;
  private final CaseActionRepository caseActions;
  private final CaseNoteRepository caseNotes;
  private final AuthorizationService authorization;
  private final TenantParameterService tenantParameters;
  private final SlaCalendarService slaCalendar;
  private final EjdGateEvaluationService gateEvaluation;
  private final EjdConnectorDispatchService connectorDispatch;

  public CaseStageTransitionService(
      LegalCaseRepository legalCases,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      ProcessStageTransitionRepository stageTransitions,
      CaseValidationRepository caseValidations,
      CaseActionRepository caseActions,
      CaseNoteRepository caseNotes,
      AuthorizationService authorization,
      TenantParameterService tenantParameters,
      SlaCalendarService slaCalendar,
      EjdGateEvaluationService gateEvaluation,
      EjdConnectorDispatchService connectorDispatch) {
    this.legalCases = legalCases;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.stageTransitions = stageTransitions;
    this.caseValidations = caseValidations;
    this.caseActions = caseActions;
    this.caseNotes = caseNotes;
    this.authorization = authorization;
    this.tenantParameters = tenantParameters;
    this.slaCalendar = slaCalendar;
    this.gateEvaluation = gateEvaluation;
    this.connectorDispatch = connectorDispatch;
  }

  @Transactional(readOnly = true)
  public StageTransitionOptions preview(UUID caseId, UUID tenantId) {
    TransitionContext context = loadContext(caseId, tenantId);
    if (context == null) {
      return emptyTransitionOptions();
    }
    AdvancePreview advance = previewAdvance(context);
    RevertPreview revert = previewRevert(context);
    return new StageTransitionOptions(
        advance.canAdvance(),
        advance.blockReason(),
        advance.nextStageCode(),
        advance.nextStageLabel(),
        revert.canRevert(),
        revert.blockReason(),
        revert.previousStageCode(),
        revert.previousStageLabel());
  }

  @Transactional
  public StageRevertResult revert(UUID caseId, StageRevertRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    if (request == null || request.comment() == null || request.comment().isBlank()) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED", "Indica el motivo del retroceso (mín. 10 caracteres).");
    }
    String comment = request.comment().trim();
    if (comment.length() < 10) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "COMMENT_TOO_SHORT", "El motivo del retroceso debe tener al menos 10 caracteres.");
    }
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    TransitionContext context = loadContext(caseId, tenantId);
    if (context == null) {
      throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado.");
    }
    RevertPreview revertPreview = previewRevert(context);
    if (!revertPreview.canRevert()) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "STAGE_REVERT_BLOCKED",
          revertPreview.blockReason() != null
              ? revertPreview.blockReason()
              : "No se puede retroceder de etapa.");
    }

    ProcessStageDef targetDef = resolveRevertTarget(context, request);
    if (targetDef == null) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "NO_TARGET_STAGE", "Etapa destino de retroceso no válida.");
    }
    if (!isIncomingTransitionAllowed(context, targetDef.getCode(), context.currentStageDef().getCode())) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "TRANSITION_NOT_ALLOWED",
          "El retroceso no está permitido: no existe transición parametrizada entre estas etapas.");
    }

    Instant now = Instant.now();
    CaseStage currentRow = context.currentCaseStage();
    currentRow.markPending();
    caseStages.save(currentRow);

    CaseStage targetRow =
        context.caseStages().stream()
            .filter(row -> row.getStageDefId().equals(targetDef.getId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.CONFLICT, "STAGE_ROW_MISSING", "Instancia de etapa no encontrada."));

    targetRow.markCurrent(now);
    caseStages.save(targetRow);

    LegalCase legalCase = context.legalCase();
    legalCase.setCurrentStageId(targetRow.getId());
    applyStageSla(legalCase, tenantId, targetDef);
    legalCases.save(legalCase);

    String fromLabel = context.currentStageDef().getLabel();
    String note =
        "Retroceso de etapa: "
            + fromLabel
            + " → "
            + targetDef.getLabel()
            + ". Motivo: "
            + comment;
    caseNotes.save(CaseNote.create(tenantId, caseId, userId, note));
    caseActions.save(
        CaseAction.create(
            tenantId, caseId, "Retroceso de etapa", "STAGE_REVERTED", "COMPLETED", userId, now));

    connectorDispatch.dispatchForStage(legalCase, targetDef.getCode(), "STAGE_REVERT");

    return new StageRevertResult(fromLabel, targetDef.getLabel(), targetDef.getCode(), note);
  }

  @Transactional
  public StageAdvanceResult advance(UUID caseId, StageAdvanceRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    TransitionContext context = loadContext(caseId, tenantId);
    if (context == null) {
      throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado.");
    }
    String block = blockReason(context);
    if (block != null) {
      throw new AuthException(HttpStatus.CONFLICT, "STAGE_BLOCKED", block);
    }

    ProcessStageDef targetDef = resolveTarget(context, request);
    if (targetDef == null) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "NO_TARGET_STAGE", "No hay etapa destino disponible.");
    }

    if (!isTransitionAllowed(context, targetDef.getCode())) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "TRANSITION_NOT_ALLOWED",
          "La transición de etapa no está permitida en la parametrización.");
    }

    Instant now = Instant.now();
    CaseStage currentRow = context.currentCaseStage();
    currentRow.markCompleted(now);
    caseStages.save(currentRow);

    CaseStage nextRow =
        context.caseStages().stream()
            .filter(row -> row.getStageDefId().equals(targetDef.getId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.CONFLICT, "STAGE_ROW_MISSING", "Instancia de etapa no encontrada."));

    nextRow.markCurrent(now);
    caseStages.save(nextRow);

    LegalCase legalCase = context.legalCase();
    legalCase.setCurrentStageId(nextRow.getId());
    applyStageSla(legalCase, tenantId, targetDef);
    legalCases.save(legalCase);

    String fromLabel = context.currentStageDef().getLabel();
    String note =
        "Etapa avanzada: "
            + fromLabel
            + " → "
            + targetDef.getLabel()
            + (request != null && request.comment() != null && !request.comment().isBlank()
                ? ". Nota: " + request.comment().trim()
                : "");
    caseNotes.save(CaseNote.create(tenantId, caseId, userId, note));
    caseActions.save(
        CaseAction.create(
            tenantId,
            caseId,
            "Avance de etapa",
            "STAGE_ADVANCED",
            "COMPLETED",
            userId,
            now));

    connectorDispatch.dispatchForStage(legalCase, targetDef.getCode(), "STAGE_ADVANCE");

    return new StageAdvanceResult(
        fromLabel, targetDef.getLabel(), targetDef.getCode(), note);
  }

  private ProcessStageDef resolveTarget(TransitionContext context, StageAdvanceRequest request) {
    if (request != null && request.targetStageCode() != null && !request.targetStageCode().isBlank()) {
      String code = request.targetStageCode().trim().toLowerCase(Locale.ROOT);
      return context.stageDefByCode().get(code);
    }
    return context.nextStageDef();
  }

  private boolean isTransitionAllowed(TransitionContext context, String targetStageCode) {
    String from = context.currentStageDef().getCode();
    String to = targetStageCode.toLowerCase(Locale.ROOT);
    if (from.equals(to)) {
      return false;
    }
    return isOutgoingTransitionAllowed(context, from, to);
  }

  private boolean isIncomingTransitionAllowed(TransitionContext context, String from, String to) {
    return isOutgoingTransitionAllowed(context, from.toLowerCase(Locale.ROOT), to.toLowerCase(Locale.ROOT));
  }

  private boolean isOutgoingTransitionAllowed(TransitionContext context, String from, String to) {
    if (from.equals(to)) {
      return false;
    }
    return stageTransitions
        .findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
            context.tenantId(), context.legalCase().getProcessDefinitionId(), from, to)
        .isPresent();
  }

  private ProcessStageDef resolveRevertTarget(TransitionContext context, StageRevertRequest request) {
    if (request != null
        && request.targetStageCode() != null
        && !request.targetStageCode().isBlank()) {
      String code = request.targetStageCode().trim().toLowerCase(Locale.ROOT);
      return context.stageDefByCode().get(code);
    }
    return context.previousStageDef();
  }

  private AdvancePreview previewAdvance(TransitionContext context) {
    String block = blockReason(context);
    if (block != null) {
      return new AdvancePreview(false, block, null, null);
    }
    ProcessStageDef next = context.nextStageDef();
    if (next == null) {
      return new AdvancePreview(false, null, null, null);
    }
    return new AdvancePreview(true, null, next.getCode(), next.getLabel());
  }

  private RevertPreview previewRevert(TransitionContext context) {
    if (!CaseWorkflowTypes.isOrchestrated(context.legalCase().getCaseType())) {
      return new RevertPreview(false, "Retroceso solo disponible para EJD y ECD.", null, null);
    }
    if (context.currentStageDef() == null) {
      return new RevertPreview(false, "El expediente no tiene etapa actual.", null, null);
    }
    ProcessStageDef previous = context.previousStageDef();
    if (previous == null) {
      return new RevertPreview(false, "El expediente ya está en la primera etapa.", null, null);
    }
    if (!isIncomingTransitionAllowed(
        context, previous.getCode(), context.currentStageDef().getCode())) {
      return new RevertPreview(
          false,
          "No hay transición parametrizada "
              + previous.getCode()
              + " → "
              + context.currentStageDef().getCode()
              + " para autorizar el retroceso.",
          null,
          null);
    }
    return new RevertPreview(true, null, previous.getCode(), previous.getLabel());
  }

  private static StageTransitionOptions emptyTransitionOptions() {
    return new StageTransitionOptions(false, null, null, null, false, null, null, null);
  }

  private String blockReason(TransitionContext context) {
    if (!CaseWorkflowTypes.isOrchestrated(context.legalCase().getCaseType())) {
      return "Las transiciones automáticas solo están disponibles para EJD y ECD.";
    }
    if (context.currentStageDef() == null) {
      return "El expediente no tiene etapa actual.";
    }
    if (context.nextStageDef() == null) {
      return "El expediente ya está en la última etapa.";
    }
    boolean failValidation =
        caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(
                context.legalCase().getId(), context.tenantId())
            .stream()
            .anyMatch(v -> "FAIL".equals(v.getResult()));
    if (failValidation) {
      return "Hay validaciones en estado «No cumple». Corrige la documentación antes de avanzar.";
    }
    String gateBlock =
        gateEvaluation.blockReasonForAdvance(
            context.legalCase().getId(), context.tenantId(), context.currentStageDef().getCode());
    if (gateBlock != null) {
      return gateBlock;
    }
    return null;
  }

  private void applyStageSla(LegalCase legalCase, UUID tenantId, ProcessStageDef definition) {
    int hours = slaCalendar.resolveSlaHours(tenantId, definition.getSlaHours());
    legalCase.setSlaDueAt(slaCalendar.computeStageDueAt(tenantId, Instant.now(), hours));
  }

  private TransitionContext loadContext(UUID caseId, UUID tenantId) {
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElse(null);
    if (legalCase == null || legalCase.getProcessDefinitionId() == null) {
      return null;
    }
    List<ProcessStageDef> definitions =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            legalCase.getProcessDefinitionId(), tenantId);
    if (definitions.isEmpty()) {
      return null;
    }
    Map<String, ProcessStageDef> stageDefByCode =
        definitions.stream()
            .collect(Collectors.toMap(def -> def.getCode().toLowerCase(Locale.ROOT), Function.identity()));

    List<CaseStage> rows =
        caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(caseId, tenantId);
    CaseStage currentRow =
        rows.stream()
            .filter(row -> "current".equals(row.getStatus()))
            .findFirst()
            .orElse(null);
    if (currentRow == null) {
      return null;
    }
    ProcessStageDef currentDef =
        definitions.stream()
            .filter(def -> def.getId().equals(currentRow.getStageDefId()))
            .findFirst()
            .orElse(null);
    ProcessStageDef nextDef = null;
    ProcessStageDef previousDef = null;
    if (currentDef != null) {
      nextDef =
          definitions.stream()
              .filter(def -> def.getSortOrder() > currentDef.getSortOrder())
              .min(Comparator.comparingInt(ProcessStageDef::getSortOrder))
              .orElse(null);
      previousDef =
          definitions.stream()
              .filter(def -> def.getSortOrder() < currentDef.getSortOrder())
              .max(Comparator.comparingInt(ProcessStageDef::getSortOrder))
              .orElse(null);
    }
    return new TransitionContext(
        tenantId,
        legalCase,
        definitions,
        stageDefByCode,
        rows,
        currentRow,
        currentDef,
        nextDef,
        previousDef);
  }

  private record AdvancePreview(
      boolean canAdvance, String blockReason, String nextStageCode, String nextStageLabel) {}

  private record RevertPreview(
      boolean canRevert, String blockReason, String previousStageCode, String previousStageLabel) {}

  private record TransitionContext(
      UUID tenantId,
      LegalCase legalCase,
      List<ProcessStageDef> definitions,
      Map<String, ProcessStageDef> stageDefByCode,
      List<CaseStage> caseStages,
      CaseStage currentCaseStage,
      ProcessStageDef currentStageDef,
      ProcessStageDef nextStageDef,
      ProcessStageDef previousStageDef) {}
}
