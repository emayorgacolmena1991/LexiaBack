package com.lexia.api.modules.expedientes.ejd;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "ejd_stage_gate_req")
public class EjdStageGateReq {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "stage_code", nullable = false, length = 16)
  private String stageCode;

  @Column(name = "gate_code", nullable = false, length = 32)
  private String gateCode;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public String getStageCode() {
    return stageCode;
  }

  public String getGateCode() {
    return gateCode;
  }

  public static EjdStageGateReq create(UUID tenantId, String stageCode, String gateCode, int sortOrder) {
    EjdStageGateReq row = new EjdStageGateReq();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.stageCode = stageCode;
    row.gateCode = gateCode;
    row.sortOrder = sortOrder;
    return row;
  }
}
