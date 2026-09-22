package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_exception")
public class CaseException {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(nullable = false, length = 240)
  private String title;

  @Column(name = "exception_type", length = 64)
  private String exceptionType;

  @Column(nullable = false, length = 16)
  private String severity;

  @Column(name = "owner_membership_id")
  private UUID ownerMembershipId;

  @Column(nullable = false, length = 32)
  private String status;

  @Column
  private String detail;

  @Column
  private String why;

  @Column
  private String evidence;

  @Column
  private String resolution;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static CaseException openFromValidation(
      UUID tenantId,
      UUID caseId,
      UUID ownerMembershipId,
      String validationLabel,
      String evidence,
      String severity) {
    CaseException row = new CaseException();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.caseId = caseId;
    row.title = validationLabel;
    row.exceptionType = "VALIDATION_FAIL";
    row.severity = severity;
    row.ownerMembershipId = ownerMembershipId;
    row.status = "OPEN";
    row.detail = "Validación en estado «No cumple».";
    row.why = "El motor LEXIA-10 reportó incumplimiento determinístico.";
    row.evidence = evidence;
    row.createdAt = Instant.now();
    row.updatedAt = row.createdAt;
    return row;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getTitle() {
    return title;
  }

  public String getExceptionType() {
    return exceptionType;
  }

  public String getSeverity() {
    return severity;
  }

  public UUID getOwnerMembershipId() {
    return ownerMembershipId;
  }

  public String getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }

  public String getWhy() {
    return why;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getResolution() {
    return resolution;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void resolve(String resolutionText) {
    this.status = "RESOLVED";
    this.resolution = resolutionText;
    this.updatedAt = Instant.now();
  }

  public void refreshEvidence(String evidenceText) {
    this.evidence = evidenceText;
    this.updatedAt = Instant.now();
  }
}
