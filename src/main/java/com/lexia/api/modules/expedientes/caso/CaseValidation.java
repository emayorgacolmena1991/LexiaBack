package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_validation")
public class CaseValidation {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "validation_def_id")
  private UUID validationDefId;

  @Column(nullable = false, length = 200)
  private String label;

  @Column(nullable = false, length = 40)
  private String kind;

  @Column(nullable = false, length = 32)
  private String result;

  @Column
  private String evidence;

  @Column(name = "rule_version", length = 32)
  private String ruleVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CaseValidation create(
      UUID tenantId,
      UUID caseId,
      UUID validationDefId,
      String label,
      String kind,
      String result,
      String ruleVersion) {
    CaseValidation validation = new CaseValidation();
    validation.id = UUID.randomUUID();
    validation.tenantId = tenantId;
    validation.caseId = caseId;
    validation.validationDefId = validationDefId;
    validation.label = label;
    validation.kind = kind;
    validation.result = result;
    validation.ruleVersion = ruleVersion;
    validation.createdAt = Instant.now();
    return validation;
  }

  public UUID getId() {
    return id;
  }

  public UUID getValidationDefId() {
    return validationDefId;
  }

  public String getLabel() {
    return label;
  }

  public String getKind() {
    return kind;
  }

  public String getResult() {
    return result;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getRuleVersion() {
    return ruleVersion;
  }

  public void applyEvaluation(String result, String evidence, String ruleVersion) {
    this.result = result;
    this.evidence = evidence;
    this.ruleVersion = ruleVersion;
  }
}
