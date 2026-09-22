package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "app_user")
public class AppUser {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "notify_on_login", nullable = false)
  private boolean notifyOnLogin = true;

  @Column(nullable = false, length = 10)
  private String locale = "es";

  @Column(length = 64)
  private String timezone;

  @Column(nullable = false, length = 16)
  private String theme = "light";

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getStatus() {
    return status;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setLockedUntil(Instant lockedUntil) {
    this.lockedUntil = lockedUntil;
  }

  public boolean isNotifyOnLogin() {
    return notifyOnLogin;
  }

  public void setNotifyOnLogin(boolean notifyOnLogin) {
    this.notifyOnLogin = notifyOnLogin;
  }

  public String getLocale() {
    return locale;
  }

  public String getTimezone() {
    return timezone;
  }

  public String getTheme() {
    return theme;
  }

  public void updateDisplayName(String displayName) {
    this.displayName = displayName.trim();
    this.updatedAt = Instant.now();
  }

  public void updatePreferences(String locale, String timezone, String theme) {
    this.locale = locale;
    this.timezone = timezone == null || timezone.isBlank() ? null : timezone.trim();
    this.theme = theme;
    this.updatedAt = Instant.now();
  }

  public static AppUser create(String email, String displayName) {
    AppUser user = new AppUser();
    user.id = UUID.randomUUID();
    user.email = email.toLowerCase().trim();
    user.displayName = displayName.trim();
    user.status = "ACTIVE";
    user.notifyOnLogin = true;
    user.locale = "es";
    user.theme = "light";
    Instant now = Instant.now();
    user.createdAt = now;
    user.updatedAt = now;
    return user;
  }
}
