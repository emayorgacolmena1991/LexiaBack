package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "process_stage_transition")
public class ProcessStageTransition {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(name = "from_stage_code", nullable = false, length = 16)
  private String fromStageCode;

  @Column(name = "to_stage_code", nullable = false, length = 16)
  private String toStageCode;

  public String getFromStageCode() {
    return fromStageCode;
  }

  public String getToStageCode() {
    return toStageCode;
  }

  public static ProcessStageTransition create(
      UUID tenantId, UUID processDefinitionId, String fromStageCode, String toStageCode) {
    ProcessStageTransition row = new ProcessStageTransition();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.processDefinitionId = processDefinitionId;
    row.fromStageCode = fromStageCode;
    row.toStageCode = toStageCode;
    return row;
  }
}
