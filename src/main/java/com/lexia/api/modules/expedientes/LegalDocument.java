package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "document")
public class LegalDocument {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id")
  private UUID caseId;

  @Column(nullable = false, length = 320)
  private String name;

  @Column(name = "doc_type", length = 80)
  private String docType;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public UUID getId() {
    return id;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getName() {
    return name;
  }

  public String getDocType() {
    return docType;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
