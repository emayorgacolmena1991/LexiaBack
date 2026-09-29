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
@Table(schema = "app", name = "document_version")
public class DocumentVersion implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

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

  @Column(name = "content_hash", nullable = false, length = 128)
  private String contentHash;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static DocumentVersion upload(
      UUID tenantId,
      UUID documentId,
      int versionNo,
      String mimeType,
      String contentHash,
      String storageKey) {
    DocumentVersion version = new DocumentVersion();
    version.id = UUID.randomUUID();
    version.tenantId = tenantId;
    version.documentId = documentId;
    version.versionNo = versionNo;
    version.origin = "UPLOAD";
    version.mimeType = mimeType;
    version.contentHash = contentHash;
    version.storageKey = storageKey;
    version.status = "STORED";
    version.createdAt = Instant.now();
    version.isNew = true;
    return version;
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

  public UUID getDocumentId() {
    return documentId;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getMimeType() {
    return mimeType;
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
