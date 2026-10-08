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
    validation.result = normalizar(result);
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void applyEvaluation(String result, String evidence, String ruleVersion) {
    this.result = normalizar(result);
    this.evidence = evidence;
    this.ruleVersion = ruleVersion;
  }

  /** CHECK case_validation_result_check: PASS, FAIL, REVIEW_REQUIRED, NOT_APPLICABLE, NOT_RUN. */
  private static String normalizar(String result) {
    if (result == null || result.isBlank()) {
      return "NOT_RUN";
    }
    return switch (result.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "PASS", "PASSED", "OK", "LEGIBLE" -> "PASS";
      case "FAIL", "FAILED", "ERROR" -> "FAIL";
      case "NOT_APPLICABLE", "NA" -> "NOT_APPLICABLE";
      case "NOT_RUN", "PENDING", "PENDIENTE" -> "NOT_RUN";
      case "REVIEW_REQUIRED", "OBSERVATION", "WARNING" -> "REVIEW_REQUIRED";
      default -> "REVIEW_REQUIRED";
    };
  }
}
