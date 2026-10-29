package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "legal_entity")
public class LegalEntity {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(length = 32)
  private String nit;

  @Column(name = "legal_name", nullable = false, length = 240)
  private String legalName;

  @Column(name = "trade_name", length = 240)
  private String tradeName;

  @Column(name = "entity_type", nullable = false, length = 32)
  private String entityType;

  @Column(length = 320)
  private String domicile;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getLegalName() {
    return legalName;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public static LegalEntity create(
      UUID tenantId,
      String nit,
      String legalName,
      String tradeName,
      String entityType,
      String domicile) {
    LegalEntity entity = new LegalEntity();
    entity.id = UUID.randomUUID();
    entity.tenantId = tenantId;
    entity.nit = nit != null && !nit.isBlank() ? nit.trim() : null;
    entity.legalName = legalName.trim();
    entity.tradeName = tradeName != null && !tradeName.isBlank() ? tradeName.trim() : null;
    entity.entityType = entityType != null && !entityType.isBlank() ? entityType.trim() : "COMPANY";
    entity.domicile = domicile != null && !domicile.isBlank() ? domicile.trim() : null;
    entity.status = "ACTIVE";
    Instant now = Instant.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }
}
