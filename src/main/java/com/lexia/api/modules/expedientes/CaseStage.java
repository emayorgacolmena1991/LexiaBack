package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_stage")
public class CaseStage {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "stage_def_id", nullable = false)
  private UUID stageDefId;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  public static CaseStage create(
      UUID tenantId, UUID caseId, UUID stageDefId, String status, Instant startedAt) {
    CaseStage stage = new CaseStage();
    stage.id = UUID.randomUUID();
    stage.tenantId = tenantId;
    stage.caseId = caseId;
    stage.stageDefId = stageDefId;
    stage.status = status;
    stage.startedAt = startedAt;
    if ("completed".equals(status)) {
      stage.completedAt = startedAt;
    }
    return stage;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStageDefId() {
    return stageDefId;
  }

  public String getStatus() {
    return status;
  }

  public void markCompleted(Instant when) {
    this.status = "completed";
    this.completedAt = when;
  }

  public void markCurrent(Instant when) {
    this.status = "current";
    this.startedAt = when;
    this.completedAt = null;
  }

  public void markPending() {
    this.status = "pending";
    this.completedAt = null;
  }
}
