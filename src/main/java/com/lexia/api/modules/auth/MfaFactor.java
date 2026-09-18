package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "mfa_factor")
public class MfaFactor {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "factor_type", nullable = false, length = 24)
  private String factorType;

  @Column(name = "secret_encrypted", nullable = false)
  private String secretEncrypted;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static MfaFactor pending(UUID userId, String secretEncrypted) {
    MfaFactor factor = new MfaFactor();
    factor.id = UUID.randomUUID();
    factor.userId = userId;
    factor.factorType = "TOTP";
    factor.secretEncrypted = secretEncrypted;
    factor.enabled = false;
    factor.createdAt = Instant.now();
    return factor;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getSecretEncrypted() {
    return secretEncrypted;
  }

  public void setSecretEncrypted(String secretEncrypted) {
    this.secretEncrypted = secretEncrypted;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setConfirmedAt(Instant confirmedAt) {
    this.confirmedAt = confirmedAt;
  }
}
