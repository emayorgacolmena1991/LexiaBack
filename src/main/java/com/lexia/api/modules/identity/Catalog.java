package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "catalog")
public class Catalog {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static Catalog create(UUID tenantId, String code, String name) {
    Catalog catalog = new Catalog();
    catalog.id = UUID.randomUUID();
    catalog.tenantId = tenantId;
    catalog.code = code.trim().toUpperCase();
    catalog.name = name.trim();
    Instant now = Instant.now();
    catalog.createdAt = now;
    catalog.updatedAt = now;
    return catalog;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void rename(String name) {
    this.name = name.trim();
    this.updatedAt = Instant.now();
  }

  public void softDelete() {
    this.deletedAt = Instant.now();
    this.updatedAt = Instant.now();
  }
}
