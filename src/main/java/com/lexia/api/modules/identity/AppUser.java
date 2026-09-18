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
}
