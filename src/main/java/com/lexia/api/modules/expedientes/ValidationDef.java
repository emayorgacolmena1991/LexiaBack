package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "validation_def")
public class ValidationDef {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(nullable = false, length = 200)
  private String label;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "rule_def_id")
  private UUID ruleDefId;

  @Column(length = 300)
  private String description;

  @Column(nullable = false)
  private boolean active = true;

  public UUID getId() {
    return id;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public UUID getRuleDefId() {
    return ruleDefId;
  }

  public void setRuleDefId(UUID ruleDefId) {
    this.ruleDefId = ruleDefId;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public static ValidationDef createNew(
      UUID tenantId, UUID processDefinitionId, String code, String label, int sortOrder) {
    ValidationDef row = new ValidationDef();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.processDefinitionId = processDefinitionId;
    row.code = code;
    row.label = label;
    row.sortOrder = sortOrder;
    return row;
  }
}
