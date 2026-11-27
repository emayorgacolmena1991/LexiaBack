package com.lexia.api.modules.expedientes.metricas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_cycle_snapshot")
public class CaseCycleSnapshot {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "case_code", nullable = false, length = 40)
  private String caseCode;

  @Column(nullable = false, length = 64)
  private String vertical;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at", nullable = false)
  private Instant completedAt;

  @Column(nullable = false, length = 32)
  private String outcome;

  @Column(name = "duration_seconds", nullable = false)
  private long durationSeconds;

  @Column(name = "stage_breakdown", columnDefinition = "jsonb")
  private String stageBreakdownJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CaseCycleSnapshot of(
      UUID tenantId,
      UUID caseId,
      String caseCode,
      String vertical,
      Instant startedAt,
      Instant completedAt,
      String outcome,
      long durationSeconds,
      String stageBreakdownJson) {
    CaseCycleSnapshot s = new CaseCycleSnapshot();
    s.id = UUID.randomUUID();
    s.tenantId = tenantId;
    s.caseId = caseId;
    s.caseCode = caseCode;
    s.vertical = vertical;
    s.startedAt = startedAt;
    s.completedAt = completedAt;
    s.outcome = outcome;
    s.durationSeconds = durationSeconds;
    s.stageBreakdownJson = stageBreakdownJson;
    s.createdAt = Instant.now();
    return s;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public long getDurationSeconds() {
    return durationSeconds;
  }

  public String getOutcome() {
    return outcome;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getCaseCode() {
    return caseCode;
  }

  public String getVertical() {
    return vertical;
  }
}