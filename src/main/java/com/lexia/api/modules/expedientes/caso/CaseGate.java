package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_gate")
public class CaseGate {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "gate_def_id", nullable = false)
  private UUID gateDefId;

  @Column(nullable = false, length = 40)
  private String result;

  public static CaseGate create(UUID tenantId, UUID caseId, UUID gateDefId, String result) {
    CaseGate gate = new CaseGate();
    gate.id = UUID.randomUUID();
    gate.tenantId = tenantId;
    gate.caseId = caseId;
    gate.gateDefId = gateDefId;
    gate.result = result;
    return gate;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGateDefId() {
    return gateDefId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getResult() {
    return result;
  }

  public void applyResult(String newResult) {
    this.result = newResult;
  }
}
