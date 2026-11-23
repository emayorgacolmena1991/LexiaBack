package com.lexia.api.modules.notary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "control", name = "tenant_notary_partnership")
public class TenantNotaryPartnership {

  @Id private UUID id;

  @Column(name = "firm_tenant_id", nullable = false)
  private UUID firmTenantId;

  @Column(name = "notary_tenant_id", nullable = false)
  private UUID notaryTenantId;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "initiated_by_tenant_id", nullable = false)
  private UUID initiatedByTenantId;

  @Column(name = "notary_contact_email", length = 320)
  private String notaryContactEmail;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "suspended_at")
  private Instant suspendedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TenantNotaryPartnership() {}

  public static TenantNotaryPartnership request(
      UUID firmTenantId, UUID notaryTenantId, String contactEmail) {
    TenantNotaryPartnership p = new TenantNotaryPartnership();
    Instant now = Instant.now();
    p.id = UUID.randomUUID();
    p.firmTenantId = firmTenantId;
    p.notaryTenantId = notaryTenantId;
    p.status = NotaryPartnershipStatus.PENDING.name();
    p.initiatedByTenantId = firmTenantId;
    p.notaryContactEmail = contactEmail;
    p.createdAt = now;
    p.updatedAt = now;
    return p;
  }

  public void approve() {
    status = NotaryPartnershipStatus.ACTIVE.name();
    approvedAt = Instant.now();
    suspendedAt = null;
    updatedAt = Instant.now();
  }

  public void reject() {
    status = NotaryPartnershipStatus.REJECTED.name();
    updatedAt = Instant.now();
  }

  public void suspend() {
    status = NotaryPartnershipStatus.SUSPENDED.name();
    suspendedAt = Instant.now();
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getFirmTenantId() {
    return firmTenantId;
  }

  public UUID getNotaryTenantId() {
    return notaryTenantId;
  }

  public String getStatus() {
    return status;
  }

  public NotaryPartnershipStatus statusEnum() {
    return NotaryPartnershipStatus.fromDb(status);
  }

  public boolean isActive() {
    return NotaryPartnershipStatus.ACTIVE.name().equals(status);
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }
}
