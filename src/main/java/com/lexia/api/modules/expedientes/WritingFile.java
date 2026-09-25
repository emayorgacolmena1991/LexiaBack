package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "writing_file")
public class WritingFile {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "property_label", length = 240)
  private String propertyLabel;

  @Column(length = 80)
  private String folio;

  @Column(name = "act_type", length = 80)
  private String actType;

  @Column(length = 160)
  private String municipality;

  @Column(name = "product_code", length = 64)
  private String productCode;

  @Column(length = 64)
  private String canton;

  @Column(name = "ingestion_mode", length = 32)
  private String ingestionMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static WritingFile create(UUID tenantId, UUID caseId) {
    WritingFile file = new WritingFile();
    file.id = UUID.randomUUID();
    file.tenantId = tenantId;
    file.caseId = caseId;
    Instant now = Instant.now();
    file.createdAt = now;
    file.updatedAt = now;
    file.rowVersion = 1L;
    return file;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getCanton() {
    return canton;
  }

  public String getIngestionMode() {
    return ingestionMode;
  }

  public String getMunicipality() {
    return municipality;
  }

  public void applyProduct(String productCode, String canton, String ingestionMode) {
    this.productCode = productCode;
    this.canton = canton;
    this.ingestionMode = ingestionMode;
    if (canton != null && !canton.isBlank()) {
      this.municipality = canton;
    }
    this.updatedAt = Instant.now();
  }
}
