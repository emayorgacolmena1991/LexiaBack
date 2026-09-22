package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_stage_def")
public class ProcessStageDef {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(nullable = false, length = 16)
  private String code;

  @Column(nullable = false, length = 160)
  private String label;

  @Column(name = "short_label", nullable = false, length = 64)
  private String shortLabel;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "sla_hours")
  private Integer slaHours;

  @Column(name = "color_key", nullable = false, length = 16)
  private String colorKey = "blue";

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public String getShortLabel() {
    return shortLabel;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public void setShortLabel(String shortLabel) {
    this.shortLabel = shortLabel;
  }

  public Integer getSlaHours() {
    return slaHours;
  }

  public void setSlaHours(Integer slaHours) {
    this.slaHours = slaHours;
  }

  public String getColorKey() {
    return colorKey;
  }

  public void setColorKey(String colorKey) {
    this.colorKey = colorKey;
  }
}
