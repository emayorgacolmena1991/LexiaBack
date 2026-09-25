package com.lexia.api.modules.expedientes.proceso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_config_change_set_item")
public class ProcessConfigChangeSetItem {

  @Id private UUID id;

  @Column(name = "change_set_id", nullable = false)
  private UUID changeSetId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 32)
  private String domain;

  @Column(nullable = false, length = 500)
  private String summary;

  @Column(name = "entity_ref", length = 128)
  private String entityRef;

  @Column(name = "last_updated_at", nullable = false)
  private Instant lastUpdatedAt;

  public static ProcessConfigChangeSetItem create(
      UUID changeSetId,
      UUID tenantId,
      String domain,
      String summary,
      String entityRef) {
    ProcessConfigChangeSetItem row = new ProcessConfigChangeSetItem();
    row.id = UUID.randomUUID();
    row.changeSetId = changeSetId;
    row.tenantId = tenantId;
    row.domain = domain;
    row.summary = summary;
    row.entityRef = entityRef;
    row.lastUpdatedAt = Instant.now();
    return row;
  }

  public void refresh(String summary, String entityRef) {
    this.summary = summary;
    this.entityRef = entityRef;
    this.lastUpdatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getChangeSetId() {
    return changeSetId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getDomain() {
    return domain;
  }

  public String getSummary() {
    return summary;
  }

  public String getEntityRef() {
    return entityRef;
  }

  public Instant getLastUpdatedAt() {
    return lastUpdatedAt;
  }
}
