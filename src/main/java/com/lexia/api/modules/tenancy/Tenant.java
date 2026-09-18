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
}
