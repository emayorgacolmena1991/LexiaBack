package com.lexia.api.modules.expedientes.proceso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_config_change_set")
public class ProcessConfigChangeSet {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(name = "case_type", nullable = false, length = 8)
  private String caseType;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "submit_comment", length = 2000)
  private String submitComment;

  @Column(name = "submitted_by")
  private UUID submittedBy;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "review_comment", length = 2000)
  private String reviewComment;

  @Column(name = "reviewed_by")
  private UUID reviewedBy;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "publication_id")
  private UUID publicationId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  public static ProcessConfigChangeSet createDraft(
      UUID tenantId, UUID processDefinitionId, String caseType, UUID createdBy) {
    ProcessConfigChangeSet row = new ProcessConfigChangeSet();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.processDefinitionId = processDefinitionId;
    row.caseType = caseType;
    row.status = "DRAFT";
    row.createdBy = createdBy;
    row.createdAt = Instant.now();
    return row;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public String getCaseType() {
    return caseType;
  }

  public String getStatus() {
    return status;
  }

  public String getSubmitComment() {
    return submitComment;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public String getReviewComment() {
    return reviewComment;
  }

  public UUID getReviewedBy() {
    return reviewedBy;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public UUID getPublicationId() {
    return publicationId;
  }

  public void submitForReview(String comment, UUID userId) {
    this.status = "REVIEW";
    this.submitComment = comment;
    this.submittedBy = userId;
    this.submittedAt = Instant.now();
    this.reviewComment = null;
    this.reviewedBy = null;
    this.reviewedAt = null;
  }

  public void approve(String comment, UUID userId) {
    this.status = "APPROVED";
    this.reviewComment = comment;
    this.reviewedBy = userId;
    this.reviewedAt = Instant.now();
  }

  public void reject(String comment, UUID userId) {
    this.status = "REJECTED";
    this.reviewComment = comment;
    this.reviewedBy = userId;
    this.reviewedAt = Instant.now();
  }

  public void markPublished(UUID publicationId) {
    this.status = "PUBLISHED";
    this.publicationId = publicationId;
  }
}
