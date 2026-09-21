package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "role")
public class Role {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(length = 200)
  private String description;

  @Column(name = "is_system", nullable = false)
  private boolean system;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public boolean isSystem() {
    return system;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public static Role create(UUID tenantId, String code, String name, String description) {
    Role role = new Role();
    role.id = UUID.randomUUID();
    role.tenantId = tenantId;
    role.code = normalizeCode(code);
    role.name = name.trim();
    role.description = normalizeDescription(description);
    role.system = false;
    Instant now = Instant.now();
    role.createdAt = now;
    role.updatedAt = now;
    return role;
  }

  public void updateDetails(String name, String description) {
    this.name = name.trim();
    this.description = normalizeDescription(description);
    this.updatedAt = Instant.now();
  }

  private static String normalizeDescription(String description) {
    if (description == null) {
      return null;
    }
    String trimmed = description.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public void softDelete() {
    this.deletedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  private static String normalizeCode(String code) {
    return code.trim().toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
  }
}
