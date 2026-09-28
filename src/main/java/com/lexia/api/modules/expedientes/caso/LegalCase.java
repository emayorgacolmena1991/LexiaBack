package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "app", name = "legal_case")
public class LegalCase implements Persistable<UUID> {

  @Id private UUID id;

  @Transient private boolean isNew = true;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 40)
  private String code;

  @Column(name = "case_type", nullable = false, length = 8)
  private String caseType;

  @Column(nullable = false, length = 64)
  private String vertical;

  @Column(nullable = false, length = 400)
  private String subject;

  @Column(nullable = false, length = 40)
  private String status;

  @Column(nullable = false, length = 16)
  private String priority;

  @Column(name = "responsible_membership_id")
  private UUID responsibleMembershipId;

  @Column(name = "process_definition_id")
  private UUID processDefinitionId;

  @Column(name = "current_stage_id")
  private UUID currentStageId;

  @Column(name = "sla_due_at")
  private Instant slaDueAt;

  @Column(name = "operation_type_code", length = 64)
  private String operationTypeCode;

  @Column(name = "product_code", length = 64)
  private String productCode;

  @Column(name = "ingestion_mode", length = 32)
  private String ingestionMode;

  @Column(name = "process_config_version")
  private Integer processConfigVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;

  public static LegalCase create(
      UUID tenantId,
      String code,
      String caseType,
      String vertical,
      String subject,
      String priority,
      UUID responsibleMembershipId,
      UUID createdBy,
      Instant slaDueAt) {
    LegalCase legalCase = new LegalCase();
    legalCase.id = UUID.randomUUID();
    legalCase.tenantId = tenantId;
    legalCase.code = code;
    legalCase.caseType = caseType;
    legalCase.vertical = vertical;
    legalCase.subject = subject;
    legalCase.status = "DRAFT";
    legalCase.priority = priority;
    legalCase.responsibleMembershipId = responsibleMembershipId;
    legalCase.slaDueAt = slaDueAt;
    legalCase.createdBy = createdBy;
    legalCase.updatedBy = createdBy;
    Instant now = Instant.now();
    legalCase.createdAt = now;
    legalCase.updatedAt = now;
    legalCase.isNew = true;
    return legalCase;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getCode() {
    return code;
  }

  public String getCaseType() {
    return caseType;
  }

  public String getVertical() {
    return vertical;
  }

  public String getSubject() {
    return subject;
  }

  public String getStatus() {
    return status;
  }

  public String getPriority() {
    return priority;
  }

  public UUID getResponsibleMembershipId() {
    return responsibleMembershipId;
  }

  public Instant getSlaDueAt() {
    return slaDueAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public UUID getCurrentStageId() {
    return currentStageId;
  }

  public void setSlaDueAt(Instant slaDueAt) {
    this.slaDueAt = slaDueAt;
  }

  public void attachProcess(UUID processDefinitionId, UUID currentStageId) {
    attachProcess(processDefinitionId, currentStageId, null);
  }

  public void attachProcess(UUID processDefinitionId, UUID currentStageId, Integer configVersion) {
    this.processDefinitionId = processDefinitionId;
    this.currentStageId = currentStageId;
    this.processConfigVersion = configVersion;
    this.updatedAt = Instant.now();
  }

  public Integer getProcessConfigVersion() {
    return processConfigVersion;
  }

  public String getOperationTypeCode() {
    return operationTypeCode;
  }

  public void setOperationTypeCode(String operationTypeCode) {
    this.operationTypeCode = operationTypeCode;
    this.updatedAt = Instant.now();
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
    this.updatedAt = Instant.now();
  }

  public String getIngestionMode() {
    return ingestionMode;
  }

  public void setIngestionMode(String ingestionMode) {
    this.ingestionMode = ingestionMode;
    this.updatedAt = Instant.now();
  }

  public void setCurrentStageId(UUID currentStageId) {
    this.currentStageId = currentStageId;
    this.updatedAt = Instant.now();
  }
}
