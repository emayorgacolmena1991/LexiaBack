package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.expedientes.caso.CaseBootstrapService;
import com.lexia.api.modules.expedientes.caso.CaseStage;
import com.lexia.api.modules.expedientes.caso.CaseStageRepository;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageTransitionRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mantiene el {@link LegalCase} (tipo ECD) asociado a cada expediente coactivo: lo crea con sus
 * etapas y mueve la etapa actual cuando se confirma la etapa procesal.
 */
@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaCaseSync {

  static final String CASE_TYPE = "ECD";
  static final String VERTICAL = "Coactivas";

  private final LegalCaseRepository legalCases;
  private final CaseBootstrapService bootstrap;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final ProcessStageTransitionRepository transitions;
  private final SlaCalendarService slaCalendar;

  public CoactivaCaseSync(
      LegalCaseRepository legalCases,
      CaseBootstrapService bootstrap,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      ProcessStageTransitionRepository transitions,
      SlaCalendarService slaCalendar) {
    this.legalCases = legalCases;
    this.bootstrap = bootstrap;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.transitions = transitions;
    this.slaCalendar = slaCalendar;
  }

  public LegalCase crearCaso(
      UUID tenantId,
      UUID userId,
      UUID membershipId,
      String nroJuicio,
      String deudorNombre,
      String deudorIdentificacion) {
    String code = allocateCode(tenantId, nroJuicio);
    int hours = slaCalendar.resolveSlaHours(tenantId, null);
    Instant slaDue = slaCalendar.computeStageDueAt(tenantId, Instant.now(), hours);
    String subject = "Juicio coactivo " + nroJuicio + (deudorNombre == null ? "" : " — " + deudorNombre);
    LegalCase legalCase =
        legalCases.save(
            LegalCase.create(
                tenantId,
                code,
                CASE_TYPE,
                VERTICAL,
                subject.length() > 400 ? subject.substring(0, 400) : subject,
                "MEDIA",
                membershipId,
                userId,
                slaDue));
    CreateCaseRequest request =
        new CreateCaseRequest(
            legalCase.getSubject(),
            VERTICAL,
            CASE_TYPE,
            deudorNombre,
            deudorIdentificacion,
            CoactivaEtapa.PREVIA.label(),
            "MEDIA",
            null,
            null,
            null,
            null,
            null);
    bootstrap.bootstrap(legalCase, request, tenantId, userId);
    return legalCase;
  }

  public Optional<LegalCase> caso(UUID tenantId, UUID caseId) {
    return legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId);
  }

  /** Marca la etapa del caso que corresponde a la etapa procesal como actual. */
  public void sincronizarEtapa(UUID tenantId, UUID caseId, CoactivaEtapa etapa) {
    LegalCase legalCase = legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    if (legalCase == null) {
      return;
    }
    List<CaseStage> stages = caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(caseId, tenantId);
    Map<UUID, ProcessStageDef> defs = new HashMap<>();
    for (CaseStage stage : stages) {
      stageDefs.findByIdAndTenantId(stage.getStageDefId(), tenantId).ifPresent(d -> defs.put(d.getId(), d));
    }
    ProcessStageDef target =
        defs.values().stream().filter(d -> etapa.stageCode().equals(d.getCode())).findFirst().orElse(null);
    if (target == null) {
      return;
    }
    Instant now = Instant.now();
    for (CaseStage stage : stages) {
      ProcessStageDef def = defs.get(stage.getStageDefId());
      if (def == null) {
        continue;
      }
      if (def.getId().equals(target.getId())) {
        if (!"current".equals(stage.getStatus())) {
          stage.markCurrent(now);
        }
        legalCase.setCurrentStageId(stage.getId());
      } else if (def.getSortOrder() < target.getSortOrder()) {
        if (!"completed".equals(stage.getStatus())) {
          stage.markCompleted(now);
        }
      } else if (!"pending".equals(stage.getStatus())) {
        stage.markPending();
      }
    }
  }

  /** ¿Existe la transición from → to en la configuración del proceso ECD del caso? */
  public boolean transicionPermitida(UUID tenantId, UUID caseId, CoactivaEtapa desde, CoactivaEtapa hacia) {
    LegalCase legalCase = legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    if (legalCase == null || legalCase.getProcessDefinitionId() == null) {
      return true;
    }
    return transitions
        .findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
            tenantId, legalCase.getProcessDefinitionId(), desde.stageCode(), hacia.stageCode())
        .isPresent();
  }

  public List<String> etapasSiguientes(UUID tenantId, UUID caseId, CoactivaEtapa desde) {
    LegalCase legalCase = legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    if (desde == null || legalCase == null || legalCase.getProcessDefinitionId() == null) {
      return java.util.Arrays.stream(CoactivaEtapa.values()).map(Enum::name).toList();
    }
    return transitions
        .findByTenantIdAndProcessDefinitionIdOrderByFromStageCodeAsc(tenantId, legalCase.getProcessDefinitionId())
        .stream()
        .filter(t -> desde.stageCode().equals(t.getFromStageCode()))
        .map(t -> CoactivaEtapa.fromStageCode(t.getToStageCode()))
        .flatMap(Optional::stream)
        .map(Enum::name)
        .toList();
  }

  private String allocateCode(UUID tenantId, String nroJuicio) {
    String base = "COA-" + nroJuicio;
    if (base.length() > 36) {
      base = base.substring(0, 36);
    }
    String code = base;
    int suffix = 2;
    while (legalCases.existsByTenantIdAndCodeAndDeletedAtIsNull(tenantId, code)) {
      code = base + "-" + suffix++;
    }
    return code;
  }
}
