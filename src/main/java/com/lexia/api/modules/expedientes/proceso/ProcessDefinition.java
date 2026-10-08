package com.lexia.api.modules.expedientes.proceso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_definition")
public class ProcessDefinition {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "case_type", nullable = false, length = 8)
  private String caseType;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "config_version", nullable = false)
  private int configVersion = 1;

  @Column(name = "has_unpublished_changes", nullable = false)
  private boolean hasUnpublishedChanges;

  @Column(name = "last_published_at")
  private Instant lastPublishedAt;

  @Column(name = "last_published_by")
  private UUID lastPublishedBy;

  @Column(name = "last_modified_at")
  private Instant lastModifiedAt;

  @Column(name = "last_modified_by")
  private UUID lastModifiedBy;

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCaseType() {
    return caseType;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public int getConfigVersion() {
    return configVersion;
  }

  public boolean isHasUnpublishedChanges() {
    return hasUnpublishedChanges;
  }

  public Instant getLastPublishedAt() {
    return lastPublishedAt;
  }

  public UUID getLastPublishedBy() {
    return lastPublishedBy;
  }

  public void setHasUnpublishedChanges(boolean hasUnpublishedChanges) {
    this.hasUnpublishedChanges = hasUnpublishedChanges;
  }

  public Instant getLastModifiedAt() {
    return lastModifiedAt;
  }

  public UUID getLastModifiedBy() {
    return lastModifiedBy;
  }

  public void touchDraft(UUID userId, Instant at) {
    this.lastModifiedAt = at;
    this.lastModifiedBy = userId;
  }

  public int publish(UUID userId) {
    this.configVersion = this.configVersion + 1;
    this.hasUnpublishedChanges = false;
    Instant now = Instant.now();
    this.lastPublishedAt = now;
    this.lastPublishedBy = userId;
    this.lastModifiedAt = now;
    this.lastModifiedBy = userId;
    return this.configVersion;
  }
}
