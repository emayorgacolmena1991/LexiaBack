package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "membership")
public class Membership {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "legal_entity_id")
  private UUID legalEntityId;

  @Column(nullable = false, length = 24)
  private String status;

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

  public UUID getUserId() {
    return userId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setStatus(String status) {
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public void suspend() {
    this.status = "SUSPENDED";
    this.updatedAt = Instant.now();
  }

  public static Membership invited(UUID userId, UUID tenantId) {
    Membership membership = new Membership();
    membership.id = UUID.randomUUID();
    membership.userId = userId;
    membership.tenantId = tenantId;
    membership.status = "INVITED";
    Instant now = Instant.now();
    membership.createdAt = now;
    membership.updatedAt = now;
    return membership;
  }
}
