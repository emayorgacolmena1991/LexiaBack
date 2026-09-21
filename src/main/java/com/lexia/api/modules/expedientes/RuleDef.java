package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "rule_def")
public class RuleDef {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false, length = 200)
  private String name;

  @Column
  private String body;

  @Column(nullable = false, length = 24)
  private String status;

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public int getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public String getBody() {
    return body;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void submitForReview() {
    if (!"DRAFT".equals(status)) {
      throw new IllegalStateException("Solo borradores pueden enviarse a revisión.");
    }
    status = "REVIEW";
  }

  public void approve() {
    if (!"REVIEW".equals(status)) {
      throw new IllegalStateException("Solo reglas en revisión pueden aprobarse.");
    }
    status = "APPROVED";
  }

  public void activate() {
    if (!"APPROVED".equals(status) && !"SUSPENDED".equals(status)) {
      throw new IllegalStateException("Solo reglas aprobadas pueden activarse.");
    }
    status = "ACTIVE";
  }

  public void retire() {
    status = "RETIRED";
  }

  public void updateDraftBody(String nextBody) {
    if (!"DRAFT".equals(status)) {
      throw new IllegalStateException("Solo borradores pueden editarse.");
    }
    body = nextBody;
  }
}
