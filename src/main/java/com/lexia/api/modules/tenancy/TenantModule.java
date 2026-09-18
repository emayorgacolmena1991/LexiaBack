package com.lexia.api.modules.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "tenant_module")
public class TenantModule {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "module_code", nullable = false, length = 64)
  private String moduleCode;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getTenantId() {
    return tenantId;
  }

  public String getModuleCode() {
    return moduleCode;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
