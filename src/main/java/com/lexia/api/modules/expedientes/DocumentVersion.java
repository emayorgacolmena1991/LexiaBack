package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "document_version")
public class DocumentVersion {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @Column(nullable = false, length = 32)
  private String origin;

  @Column(name = "mime_type", length = 128)
  private String mimeType;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getDocumentId() {
    return documentId;
  }

  public int getVersionNo() {
    return versionNo;
  }

  public String getOrigin() {
    return origin;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
