package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "recovery_code")
public class RecoveryCode {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "code_hash", nullable = false)
  private String codeHash;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static RecoveryCode of(UUID userId, String codeHash) {
    RecoveryCode code = new RecoveryCode();
    code.id = UUID.randomUUID();
    code.userId = userId;
    code.codeHash = codeHash;
    code.createdAt = Instant.now();
    return code;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(Instant usedAt) {
    this.usedAt = usedAt;
  }
}
