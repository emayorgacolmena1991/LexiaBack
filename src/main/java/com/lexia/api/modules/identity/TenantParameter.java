package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "tenant_parameter")
public class TenantParameter {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "param_key", nullable = false, length = 96)
  private String paramKey;

  @Column(name = "param_value", nullable = false)
  private String paramValue;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static TenantParameter of(UUID tenantId, String paramKey, String paramValue) {
    TenantParameter parameter = new TenantParameter();
    parameter.id = UUID.randomUUID();
    parameter.tenantId = tenantId;
    parameter.paramKey = paramKey;
    parameter.paramValue = paramValue;
    Instant now = Instant.now();
    parameter.createdAt = now;
    parameter.updatedAt = now;
    return parameter;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getParamKey() {
    return paramKey;
  }

  public String getParamValue() {
    return paramValue;
  }

  public void setParamValue(String paramValue) {
    this.paramValue = paramValue;
    this.updatedAt = Instant.now();
  }
}
