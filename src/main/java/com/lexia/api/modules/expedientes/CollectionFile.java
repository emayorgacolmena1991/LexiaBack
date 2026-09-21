package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "collection_file")
public class CollectionFile {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "creditor_label", length = 240)
  private String creditorLabel;

  @Column(name = "opening_ref", length = 80)
  private String openingRef;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static CollectionFile create(UUID tenantId, UUID caseId, String creditorLabel) {
    CollectionFile row = new CollectionFile();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.caseId = caseId;
    row.creditorLabel = creditorLabel;
    row.openingRef = null;
    row.createdAt = Instant.now();
    row.updatedAt = row.createdAt;
    return row;
  }
}
