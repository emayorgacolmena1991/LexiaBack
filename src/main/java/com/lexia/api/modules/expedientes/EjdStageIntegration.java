package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "ejd_stage_integration")
public class EjdStageIntegration {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "stage_code", nullable = false, length = 16)
  private String stageCode;

  @Column(name = "integration_code", nullable = false, length = 64)
  private String integrationCode;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public String getStageCode() {
    return stageCode;
  }

  public String getIntegrationCode() {
    return integrationCode;
  }

  public static EjdStageIntegration create(
      UUID tenantId, String stageCode, String integrationCode, int sortOrder) {
    EjdStageIntegration row = new EjdStageIntegration();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.stageCode = stageCode;
    row.integrationCode = integrationCode;
    row.sortOrder = sortOrder;
    return row;
  }
}
