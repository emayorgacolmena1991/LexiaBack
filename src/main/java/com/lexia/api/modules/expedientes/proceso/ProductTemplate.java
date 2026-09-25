package com.lexia.api.modules.expedientes.proceso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "product_template")
public class ProductTemplate {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "product_code", nullable = false, length = 64)
  private String productCode;

  @Column(name = "template_kind", nullable = false, length = 32)
  private String templateKind;

  @Column(nullable = false, length = 200)
  private String label;

  @Column(name = "storage_key", length = 512)
  private String storageKey;

  @Column(name = "company_supplies_cv", nullable = false)
  private boolean companySuppliesCv;

  @Column(nullable = false)
  private boolean active = true;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public UUID getId() {
    return id;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getTemplateKind() {
    return templateKind;
  }

  public String getLabel() {
    return label;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public boolean isCompanySuppliesCv() {
    return companySuppliesCv;
  }

  public boolean isActive() {
    return active;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
