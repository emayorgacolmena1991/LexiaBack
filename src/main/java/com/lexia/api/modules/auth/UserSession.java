package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "user_session")
public class UserSession {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "membership_id")
  private UUID membershipId;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public static UserSession open(
      UUID userId, UUID tenantId, UUID membershipId, String ip, String userAgent, Instant expiresAt) {
    UserSession session = new UserSession();
    session.id = UUID.randomUUID();
    session.userId = userId;
    session.tenantId = tenantId;
    session.membershipId = membershipId;
    session.ipAddress = ip;
    session.userAgent = userAgent;
    session.createdAt = Instant.now();
    session.expiresAt = expiresAt;
    return session;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getMembershipId() {
    return membershipId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isActive() {
    return revokedAt == null && Instant.now().isBefore(expiresAt);
  }

  public void revoke() {
    this.revokedAt = Instant.now();
  }
}
