package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "user_invitation")
public class UserInvitation {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(name = "membership_id", nullable = false)
  private UUID membershipId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static UserInvitation issue(
      UUID tenantId,
      String email,
      String displayName,
      String tokenHash,
      UUID invitedBy,
      UUID membershipId,
      Instant expiresAt) {
    UserInvitation invitation = new UserInvitation();
    invitation.id = UUID.randomUUID();
    invitation.tenantId = tenantId;
    invitation.email = email.toLowerCase();
    invitation.displayName = displayName;
    invitation.tokenHash = tokenHash;
    invitation.invitedBy = invitedBy;
    invitation.membershipId = membershipId;
    invitation.expiresAt = expiresAt;
    invitation.createdAt = Instant.now();
    return invitation;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public UUID getMembershipId() {
    return membershipId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getAcceptedAt() {
    return acceptedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isPending() {
    return acceptedAt == null && revokedAt == null && Instant.now().isBefore(expiresAt);
  }

  public void accept() {
    this.acceptedAt = Instant.now();
  }

  public void revoke() {
    this.revokedAt = Instant.now();
  }
}
