package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "auth_challenge")
public class AuthChallenge {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 32)
  private String purpose;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static AuthChallenge loginMfa(UUID userId, Instant expiresAt) {
    AuthChallenge challenge = new AuthChallenge();
    challenge.id = UUID.randomUUID();
    challenge.userId = userId;
    challenge.purpose = "LOGIN_MFA";
    challenge.expiresAt = expiresAt;
    challenge.createdAt = Instant.now();
    return challenge;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public boolean isUsable() {
    return consumedAt == null && Instant.now().isBefore(expiresAt);
  }

  public void consume() {
    this.consumedAt = Instant.now();
  }
}