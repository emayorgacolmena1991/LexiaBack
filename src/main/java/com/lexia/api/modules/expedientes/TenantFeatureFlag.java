package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "tenant_feature_flag")
public class TenantFeatureFlag {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false)
  private boolean enabled;

  @Column(nullable = false, length = 500)
  private String description;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCode() {
    return code;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getDescription() {
    return description;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch(UUID userId) {
    this.updatedAt = Instant.now();
    this.updatedBy = userId;
  }
}
