package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "document")
public class LegalDocument implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

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

  public static LegalDocument create(UUID tenantId, UUID caseId, String name, String docType) {
    LegalDocument doc = new LegalDocument();
    doc.id = UUID.randomUUID();
    doc.tenantId = tenantId;
    doc.caseId = caseId;
    doc.name = name;
    doc.docType = docType;
    Instant now = Instant.now();
    doc.createdAt = now;
    doc.updatedAt = now;
    doc.isNew = true;
    return doc;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getTenantId() {
    return tenantId;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void rename(String name, String docType) {
    if (name != null && !name.isBlank()) {
      this.name = name;
    }
    this.docType = docType;
    this.updatedAt = Instant.now();
  }

  public void classify(String docType) {
    this.docType = docType;
    this.updatedAt = Instant.now();
  }

  public void softDelete() {
    Instant now = Instant.now();
    this.deletedAt = now;
    this.updatedAt = now;
  }
}
