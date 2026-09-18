package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "user_credential")
public class UserCredential {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(nullable = false, length = 32)
  private String algorithm;

  @Column(name = "last_changed_at", nullable = false)
  private Instant lastChangedAt;

  @Column(name = "must_change", nullable = false)
  private boolean mustChange;

  public UUID getUserId() {
    return userId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public void setAlgorithm(String algorithm) {
    this.algorithm = algorithm;
  }

  public void setLastChangedAt(Instant lastChangedAt) {
    this.lastChangedAt = lastChangedAt;
  }

  public void setMustChange(boolean mustChange) {
    this.mustChange = mustChange;
  }
}
