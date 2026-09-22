package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "catalog_item")
public class CatalogItem {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "catalog_id", nullable = false)
  private UUID catalogId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 200)
  private String label;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(nullable = false)
  private boolean active = true;

  public static CatalogItem create(
      UUID tenantId, UUID catalogId, String code, String label, int sortOrder) {
    CatalogItem item = new CatalogItem();
    item.id = UUID.randomUUID();
    item.tenantId = tenantId;
    item.catalogId = catalogId;
    item.code = code.trim().toUpperCase();
    item.label = label.trim();
    item.sortOrder = sortOrder;
    item.active = true;
    return item;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getCatalogId() {
    return catalogId;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public void update(String label, int sortOrder, boolean active) {
    this.label = label.trim();
    this.sortOrder = sortOrder;
    this.active = active;
  }
}
