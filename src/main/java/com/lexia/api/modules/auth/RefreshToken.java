package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "refresh_token")
public class RefreshToken {

  @Id private UUID id;

  @Column(name = "session_id", nullable = false)
  private UUID sessionId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by")
  private UUID replacedBy;

  public static RefreshToken issue(UUID sessionId, String tokenHash, UUID familyId, Instant expiresAt) {
    RefreshToken token = new RefreshToken();
    token.id = UUID.randomUUID();
    token.sessionId = sessionId;
    token.tokenHash = tokenHash;
    token.familyId = familyId;
    token.expiresAt = expiresAt;
    return token;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void revoke(UUID replacedBy) {
    this.revokedAt = Instant.now();
    this.replacedBy = replacedBy;
  }

  public void revoke() {
    this.revokedAt = Instant.now();
  }
}
