package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_config_publication")
public class ProcessConfigPublication {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(name = "config_version", nullable = false)
  private int configVersion;

  @Column(nullable = false, length = 2000)
  private String comment;

  @Column(name = "published_by", nullable = false)
  private UUID publishedBy;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;

  @Column(name = "snapshot_json")
  private String snapshotJson;

  public static ProcessConfigPublication create(
      UUID tenantId,
      UUID processDefinitionId,
      int configVersion,
      String comment,
      UUID publishedBy,
      String snapshotJson) {
    ProcessConfigPublication row = new ProcessConfigPublication();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.processDefinitionId = processDefinitionId;
    row.configVersion = configVersion;
    row.comment = comment;
    row.publishedBy = publishedBy;
    row.publishedAt = Instant.now();
    row.snapshotJson = snapshotJson;
    return row;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public int getConfigVersion() {
    return configVersion;
  }

  public String getComment() {
    return comment;
  }

  public UUID getPublishedBy() {
    return publishedBy;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public String getSnapshotJson() {
    return snapshotJson;
  }

  public boolean hasSnapshot() {
    return snapshotJson != null && !snapshotJson.isBlank();
  }
}
