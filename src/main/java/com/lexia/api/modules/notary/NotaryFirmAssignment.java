package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "notary_firm_assignment")
public class NotaryFirmAssignment {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "notary_tenant_id", nullable = false)
  private UUID notaryTenantId;

  @Column(name = "partnership_id", nullable = false)
  private UUID partnershipId;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotaryFirmAssignment() {}

  public static NotaryFirmAssignment create(
      UUID firmTenantId, UUID notaryTenantId, UUID partnershipId) {
    NotaryFirmAssignment a = new NotaryFirmAssignment();
    Instant now = Instant.now();
    a.id = UUID.randomUUID();
    a.tenantId = firmTenantId;
    a.notaryTenantId = notaryTenantId;
    a.partnershipId = partnershipId;
    a.active = true;
    a.createdAt = now;
    a.updatedAt = now;
    return a;
  }

  public void setActive(boolean active) {
    this.active = active;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getNotaryTenantId() {
    return notaryTenantId;
  }

  public UUID getPartnershipId() {
    return partnershipId;
  }

  public boolean isActive() {
    return active;
  }
}
