package com.lexia.api.modules.expedientes.metricas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.CaseStage;
import com.lexia.api.modules.expedientes.caso.CaseStageRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEtapaHistorial;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEtapaHistorialRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.CaseCycleMetrics;
import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.CaseCycleReportPage;
import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.CaseCycleReportRow;
import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.StageIntervalItem;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseCycleMetricsService {

  private final LegalCaseRepository legalCases;
  private final CaseCycleSnapshotRepository snapshots;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final CoactivaEtapaHistorialRepository coactivaHistorial;
  private final CoactivaExpedienteRepository coactivaExpedientes;
  private final AuthorizationService authorization;
  private final ObjectMapper mapper;
  private final PlatformTransactionManager transactionManager;

  private static final Logger LOG = LoggerFactory.getLogger(CaseCycleMetricsService.class);

  public CaseCycleMetricsService(
      LegalCaseRepository legalCases,
      CaseCycleSnapshotRepository snapshots,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      CoactivaEtapaHistorialRepository coactivaHistorial,
      CoactivaExpedienteRepository coactivaExpedientes,
      AuthorizationService authorization,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.transactionManager = transactionManager;
    this.legalCases = legalCases;
    this.snapshots = snapshots;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.coactivaHistorial = coactivaHistorial;
    this.coactivaExpedientes = coactivaExpedientes;
    this.authorization = authorization;
    this.mapper = mapper;
  }

  @Transactional
  public void closeCycle(UUID tenantId, UUID caseId, String outcome) {
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElse(null);
    if (legalCase == null) {
      return;
    }
    if (legalCase.getCompletedAt() != null) {
      return;
    }
    Instant completedAt = Instant.now();
    legalCase.markCompleted(completedAt, outcome);
    legalCases.save(legalCase);
    List<StageIntervalItem> stages = buildStageIntervals(legalCase);
    long durationSeconds = ChronoUnit.SECONDS.between(legalCase.getCreatedAt(), completedAt);
    String json = writeJson(stages);
    snapshots
        .findByTenantIdAndCaseId(tenantId, caseId)
        .ifPresentOrElse(
            existing -> {},
            () ->
                snapshots.save(
                    CaseCycleSnapshot.of(
                        tenantId,
                        caseId,
                        legalCase.getCode(),
                        legalCase.getVertical(),
                        legalCase.getCreatedAt(),
                        completedAt,
                        outcome,
                        durationSeconds,
                        json)));
  }

  @Transactional(readOnly = true)
  public CaseCycleMetrics metricsForCase(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Expediente no encontrado"));
    Instant completedAt = legalCase.getCompletedAt();
    String outcome = legalCase.getCompletionOutcome();
    boolean open = completedAt == null;
    List<StageIntervalItem> stages = buildStageIntervals(legalCase);
    long duration =
        open
            ? ChronoUnit.SECONDS.between(legalCase.getCreatedAt(), Instant.now())
            : ChronoUnit.SECONDS.between(legalCase.getCreatedAt(), completedAt);
    return new CaseCycleMetrics(
        legalCase.getId().toString(),
        legalCase.getCode(),
        legalCase.getVertical(),
        legalCase.getCreatedAt(),
        completedAt,
        outcome,
        outcomeLabel(outcome),
        duration,
        open,
        stages);
  }

  @Transactional(readOnly = true)
  public CaseCycleReportPage report(int limit) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    int safeLimit = Math.min(Math.max(limit, 1), 500);
    List<CaseCycleSnapshot> rows =
        snapshots.findByTenantIdOrderByCompletedAtDesc(tenantId, PageRequest.of(0, safeLimit));
    List<Long> durations = rows.stream().map(CaseCycleSnapshot::getDurationSeconds).sorted().toList();
    long avg =
        durations.isEmpty() ? 0 : durations.stream().mapToLong(Long::longValue).sum() / durations.size();
    long median = median(durations);
    List<CaseCycleReportRow> content =
        rows.stream()
            .map(
                r ->
                    new CaseCycleReportRow(
                        r.getCaseId().toString(),
                        r.getCaseCode(),
                        r.getVertical(),
                        r.getOutcome(),
                        outcomeLabel(r.getOutcome()),
                        r.getDurationSeconds(),
                        r.getCompletedAt().toString()))
            .toList();
    return new CaseCycleReportPage(content, snapshots.countByTenantId(tenantId), avg, median);
  }

  public void closeCoactivaCycle(UUID tenantId, UUID caseId) {
    closeCycle(tenantId, caseId, CaseCycleMetricsDtos.OUTCOME_COACTIVE_ARCHIVED);
  }

  /**
   * La notaría aprueba con su propio tenant en la conexión, pero el caso y el snapshot son del
   * estudio: con RLS no se verían. Se cierra tras el commit, en una transacción nueva con el tenant
   * del estudio.
   */
  public void closeNotaryApproved(UUID firmTenantId, UUID caseId) {
    Runnable cerrar =
        () ->
            enTenant(
                firmTenantId,
                () -> closeCycle(firmTenantId, caseId, CaseCycleMetricsDtos.OUTCOME_NOTARY_APPROVED));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cerrar.run();
            }
          });
    } else {
      cerrar.run();
    }
  }

  private void enTenant(UUID tenantId, Runnable accion) {
    UUID previo = TenantContext.getTenantId();
    TenantContext.setTenantId(tenantId);
    try {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      tx.executeWithoutResult(status -> accion.run());
    } catch (RuntimeException ex) {
      LOG.warn("No se pudo cerrar el ciclo del caso en tenant {}: {}", tenantId, ex.getMessage());
    } finally {
      if (previo == null) {
        TenantContext.clear();
      } else {
        TenantContext.setTenantId(previo);
      }
    }
  }

  private List<StageIntervalItem> buildStageIntervals(LegalCase legalCase) {
    UUID tenantId = legalCase.getTenantId();
    UUID caseId = legalCase.getId();
    if (legalCase.getVertical() != null
        && legalCase.getVertical().toLowerCase(Locale.ROOT).contains("coactiv")) {
      Optional<CoactivaExpediente> coactiva =
          coactivaExpedientes.findByTenantIdAndCaseIdAndDeletedAtIsNull(tenantId, caseId);
      if (coactiva.isPresent()) {
        return coactivaStages(tenantId, coactiva.get().getId(), legalCase.getCreatedAt());
      }
    }
    return ejdStages(tenantId, caseId);
  }

  private List<StageIntervalItem> ejdStages(UUID tenantId, UUID caseId) {
    List<CaseStage> stages =
        caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(caseId, tenantId);
    List<StageIntervalItem> out = new ArrayList<>();
    for (CaseStage row : stages) {
      ProcessStageDef def =
          stageDefs.findByIdAndTenantId(row.getStageDefId(), tenantId).orElse(null);
      String code = def == null ? "?" : def.getCode();
      String label = def == null ? code : def.getLabel();
      Instant start = row.getStartedAt();
      Instant end = row.getCompletedAt();
      Long sec =
          start == null || end == null ? null : ChronoUnit.SECONDS.between(start, end);
      out.add(new StageIntervalItem(code, label, start, end, sec));
    }
    return out;
  }

  private List<StageIntervalItem> coactivaStages(UUID tenantId, UUID coactivaExpedienteId, Instant createdAt) {
    List<CoactivaEtapaHistorial> historial =
        coactivaHistorial.findByTenantIdAndExpedienteIdOrderByCreatedAtAsc(tenantId, coactivaExpedienteId);
    if (historial.isEmpty()) {
      return List.of();
    }
    List<StageIntervalItem> out = new ArrayList<>();
    Instant cursor = createdAt;
    for (CoactivaEtapaHistorial h : historial) {
      Instant at = h.getCreatedAt();
      String code = h.getEtapaNueva();
      String label = CoactivaEtapa.parse(code).map(CoactivaEtapa::label).orElse(code);
      long sec = ChronoUnit.SECONDS.between(cursor, at);
      out.add(new StageIntervalItem(code, label, cursor, at, sec));
      cursor = at;
    }
    return out;
  }

  private static long median(List<Long> sorted) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int mid = sorted.size() / 2;
    if (sorted.size() % 2 == 0) {
      return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
    return sorted.get(mid);
  }

  private static String outcomeLabel(String outcome) {
    if (outcome == null) {
      return "En curso";
    }
    return switch (outcome) {
      case CaseCycleMetricsDtos.OUTCOME_NOTARY_APPROVED -> "Aprobado en notaría";
      case CaseCycleMetricsDtos.OUTCOME_COACTIVE_ARCHIVED -> "Archivado (coactiva)";
      default -> outcome;
    };
  }

  private String writeJson(List<StageIntervalItem> stages) {
    try {
      return mapper.writeValueAsString(stages);
    } catch (JsonProcessingException ex) {
      return "[]";
    }
  }
}