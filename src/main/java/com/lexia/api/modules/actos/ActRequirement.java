package com.lexia.api.modules.actos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "act_requirement")
public class ActRequirement {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "act_item_id", nullable = false)
  private UUID actItemId;

  @Column(name = "document_item_id", nullable = false)
  private UUID documentItemId;

  @Column(nullable = false)
  private boolean required;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public UUID getId() {
    return id;
  }

  public UUID getActItemId() {
    return actItemId;
  }

  public UUID getDocumentItemId() {
    return documentItemId;
  }

  public boolean isRequired() {
    return required;
  }

  public String getDescription() {
    return description;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
