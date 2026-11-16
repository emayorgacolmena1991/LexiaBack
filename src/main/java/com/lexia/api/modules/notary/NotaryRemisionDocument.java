package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "notary_remision_document")
public class NotaryRemisionDocument {

  @Id private UUID id;

  @Column(name = "remision_id", nullable = false)
  private UUID remisionId;

  @Column(name = "version_id", nullable = false)
  private UUID versionId;

  @Column(name = "firm_tenant_id", nullable = false)
  private UUID firmTenantId;

  @Column(name = "firm_document_version_id", nullable = false)
  private UUID firmDocumentVersionId;

  @Column(name = "display_name", nullable = false, length = 500)
  private String displayName;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "status_label", nullable = false, length = 40)
  private String statusLabel;

  /** Clave del archivo de la versión enviada; {@code null} en filas anteriores a V101 sin backfill. */
  @Column(name = "storage_key", length = 512)
  private String storageKey;

  /** {@code app.document.doc_type} al enviar (V105); {@code null} sin tipo o fila sin backfill. */
  @Column(name = "doc_type", length = 80)
  private String docType;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NotaryRemisionDocument() {}

  public static NotaryRemisionDocument of(
      UUID remisionId,
      UUID versionId,
      UUID firmTenantId,
      UUID firmDocumentVersionId,
      String displayName,
      int sortOrder,
      Long sizeBytes,
      String statusLabel,
      String storageKey,
      String docType) {
    NotaryRemisionDocument d = new NotaryRemisionDocument();
    d.id = UUID.randomUUID();
    d.remisionId = remisionId;
    d.versionId = versionId;
    d.firmTenantId = firmTenantId;
    d.firmDocumentVersionId = firmDocumentVersionId;
    d.displayName = displayName;
    d.sortOrder = sortOrder;
    d.sizeBytes = sizeBytes;
    d.statusLabel = statusLabel;
    d.storageKey = storageKey;
    d.docType = docType;
    d.createdAt = Instant.now();
    return d;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRemisionId() {
    return remisionId;
  }

  public UUID getVersionId() {
    return versionId;
  }

  public UUID getFirmTenantId() {
    return firmTenantId;
  }

  public UUID getFirmDocumentVersionId() {
    return firmDocumentVersionId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public String getStatusLabel() {
    return statusLabel;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getDocType() {
    return docType;
  }
}
