package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "minuta_draft")
public class MinutaDraft {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "writing_file_id", nullable = false)
  private UUID writingFileId;

  @Column(name = "document_version_id")
  private UUID documentVersionId;

  @Column(nullable = false, length = 32)
  private String status = "DRAFT";

  @Column(name = "template_kind", length = 32)
  private String templateKind;

  @Column(name = "product_code", length = 64)
  private String productCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static MinutaDraft create(
      UUID tenantId, UUID writingFileId, String productCode, String templateKind) {
    MinutaDraft draft = new MinutaDraft();
    draft.id = UUID.randomUUID();
    draft.tenantId = tenantId;
    draft.writingFileId = writingFileId;
    draft.productCode = productCode;
    draft.templateKind = templateKind;
    draft.status = "DRAFT";
    Instant now = Instant.now();
    draft.createdAt = now;
    draft.updatedAt = now;
    return draft;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWritingFileId() {
    return writingFileId;
  }

  public String getStatus() {
    return status;
  }

  public String getTemplateKind() {
    return templateKind;
  }

  public String getProductCode() {
    return productCode;
  }

  public void markReady() {
    this.status = "READY";
    this.updatedAt = Instant.now();
  }
}
