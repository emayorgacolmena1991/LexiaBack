package com.lexia.api.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "login_attempt")
public class LoginAttempt {

  @Id private UUID id;

  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(nullable = false)
  private boolean success;

  @Column(length = 64)
  private String reason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static LoginAttempt record(String email, String ip, boolean success, String reason) {
    LoginAttempt attempt = new LoginAttempt();
    attempt.id = UUID.randomUUID();
    attempt.email = email;
    attempt.ipAddress = ip;
    attempt.success = success;
    attempt.reason = reason;
    attempt.createdAt = Instant.now();
    return attempt;
  }

  public boolean isSuccess() {
    return success;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getReason() {
    return reason;
  }

  /**
   * Acierto que cierra una racha de fallos. "MFA_REQUIRED" no: la contraseña fue buena pero falta
   * el segundo factor; si contara, se podrían probar códigos MFA sin límite intercalando logins.
   */
  public boolean cierraRachaDeFallos() {
    return success && !"MFA_REQUIRED".equals(reason);
  }
}
