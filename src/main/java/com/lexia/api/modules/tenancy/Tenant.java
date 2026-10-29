package com.lexia.api.modules.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "tenant")
public class Tenant {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(name = "isolation_mode", nullable = false, length = 32)
  private String isolationMode;

  @Column(name = "plan_code", length = 64)
  private String planCode;

  @Column(name = "max_users")
  private Integer maxUsers;

  @Column(name = "max_cases")
  private Integer maxCases;

  @Column(name = "tenant_kind", nullable = false, length = 32)
  private String tenantKind;

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

  public String getStatus() {
    return status;
  }

  public String getIsolationMode() {
    return isolationMode;
  }

  public String getTimezone() {
    return timezone;
  }

  public String getPlanCode() {
    return planCode;
  }

  public Integer getMaxUsers() {
    return maxUsers;
  }

  public Integer getMaxCases() {
    return maxCases;
  }

  public String getTenantKind() {
    return tenantKind;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public static Tenant provision(
      String code,
      String name,
      String tenantKind,
      String timezone,
      String isolationMode,
      String planCode,
      Integer maxUsers,
      Integer maxCases) {
    Tenant tenant = new Tenant();
    tenant.id = UUID.randomUUID();
    tenant.code = code.trim().toLowerCase();
    tenant.name = name.trim();
    tenant.status = "ACTIVE";
    tenant.timezone = timezone != null && !timezone.isBlank() ? timezone.trim() : "America/Guayaquil";
    tenant.isolationMode =
        isolationMode != null && !isolationMode.isBlank() ? isolationMode.trim() : "SHARED";
    tenant.planCode = planCode != null && !planCode.isBlank() ? planCode.trim() : null;
    tenant.maxUsers = maxUsers != null && maxUsers > 0 ? maxUsers : null;
    tenant.maxCases = maxCases != null && maxCases > 0 ? maxCases : null;
    tenant.tenantKind = tenantKind != null && !tenantKind.isBlank() ? tenantKind.trim() : "LEGAL_FIRM";
    Instant now = Instant.now();
    tenant.createdAt = now;
    tenant.updatedAt = now;
    return tenant;
  }

  public void updateSettings(String name, String timezone, Integer maxUsers) {
    if (name != null && !name.isBlank()) {
      this.name = name.trim();
    }
    if (timezone != null && !timezone.isBlank()) {
      this.timezone = timezone.trim();
    }
    if (maxUsers != null) {
      this.maxUsers = maxUsers <= 0 ? null : maxUsers;
    }
    this.updatedAt = Instant.now();
  }

  public void updatePlatformGovernance(
      String name, String timezone, String planCode, Integer maxUsers, Integer maxCases) {
    updateSettings(name, timezone, maxUsers);
    if (planCode != null) {
      String trimmed = planCode.trim();
      this.planCode = trimmed.isEmpty() ? null : trimmed;
    }
    if (maxCases != null) {
      this.maxCases = maxCases <= 0 ? null : maxCases;
    }
    this.updatedAt = Instant.now();
  }

  public void setOperationalStatus(String status) {
    this.status = status.trim().toUpperCase();
    this.updatedAt = Instant.now();
  }
}
