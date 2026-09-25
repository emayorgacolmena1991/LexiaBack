package com.lexia.api.modules.expedientes.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "tenant_ai_policy")
public class TenantAiPolicy {

  @Id
  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "document_extraction", nullable = false)
  private boolean documentExtraction = true;

  @Column(name = "workspace_assist", nullable = false)
  private boolean workspaceAssist = true;

  @Column(name = "routing_mode", nullable = false, length = 16)
  private String routingMode = "GEMINI";

  @Column(name = "max_daily_document_jobs", nullable = false)
  private int maxDailyDocumentJobs = 200;

  @Column(name = "min_confidence_percent", nullable = false, columnDefinition = "SMALLINT")
  private short minConfidencePercent = 70;

  @Column(name = "jobs_today_count", nullable = false)
  private int jobsTodayCount;

  @Column(name = "jobs_today_date")
  private LocalDate jobsTodayDate;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  public static TenantAiPolicy defaults(UUID tenantId) {
    TenantAiPolicy row = new TenantAiPolicy();
    row.tenantId = tenantId;
    row.updatedAt = Instant.now();
    return row;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public boolean isDocumentExtraction() {
    return documentExtraction;
  }

  public void setDocumentExtraction(boolean documentExtraction) {
    this.documentExtraction = documentExtraction;
  }

  public boolean isWorkspaceAssist() {
    return workspaceAssist;
  }

  public void setWorkspaceAssist(boolean workspaceAssist) {
    this.workspaceAssist = workspaceAssist;
  }

  public String getRoutingMode() {
    return routingMode;
  }

  public void setRoutingMode(String routingMode) {
    this.routingMode = routingMode;
  }

  public int getMaxDailyDocumentJobs() {
    return maxDailyDocumentJobs;
  }

  public void setMaxDailyDocumentJobs(int maxDailyDocumentJobs) {
    this.maxDailyDocumentJobs = maxDailyDocumentJobs;
  }

  public int getMinConfidencePercent() {
    return minConfidencePercent;
  }

  public void setMinConfidencePercent(int minConfidencePercent) {
    this.minConfidencePercent = (short) minConfidencePercent;
  }

  public int getJobsTodayCount() {
    return jobsTodayCount;
  }

  public LocalDate getJobsTodayDate() {
    return jobsTodayDate;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch(UUID userId) {
    this.updatedAt = Instant.now();
    this.updatedBy = userId;
  }

  public void reserveDocumentJobs(int fileCount, LocalDate today) {
    if (jobsTodayDate == null || !jobsTodayDate.equals(today)) {
      jobsTodayDate = today;
      jobsTodayCount = 0;
    }
    jobsTodayCount += Math.max(0, fileCount);
  }

  public boolean wouldExceedDailyQuota(int fileCount, LocalDate today) {
    int current = jobsTodayCount;
    if (jobsTodayDate == null || !jobsTodayDate.equals(today)) {
      current = 0;
    }
    return current + Math.max(0, fileCount) > maxDailyDocumentJobs;
  }
}
