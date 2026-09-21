package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "gate_def")
public class GateDef {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "process_definition_id", nullable = false)
  private UUID processDefinitionId;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(nullable = false)
  private String question;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "response_type", nullable = false, length = 16)
  private String responseType = "yes_no";

  @Column(name = "continue_criterion", nullable = false, length = 16)
  private String continueCriterion = "affirmative";

  @Column(name = "mandatory", nullable = false)
  private boolean mandatory = true;

  @Column(name = "message_ok", length = 500)
  private String messageOk;

  @Column(name = "message_fail", length = 500)
  private String messageFail;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getQuestion() {
    return question;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public UUID getProcessDefinitionId() {
    return processDefinitionId;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getResponseType() {
    return responseType;
  }

  public void setResponseType(String responseType) {
    this.responseType = responseType;
  }

  public String getContinueCriterion() {
    return continueCriterion;
  }

  public void setContinueCriterion(String continueCriterion) {
    this.continueCriterion = continueCriterion;
  }

  public boolean isMandatory() {
    return mandatory;
  }

  public void setMandatory(boolean mandatory) {
    this.mandatory = mandatory;
  }

  public String getMessageOk() {
    return messageOk;
  }

  public void setMessageOk(String messageOk) {
    this.messageOk = messageOk;
  }

  public String getMessageFail() {
    return messageFail;
  }

  public void setMessageFail(String messageFail) {
    this.messageFail = messageFail;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public static GateDef createNew(
      UUID tenantId, UUID processDefinitionId, String code, String question, int sortOrder) {
    GateDef gate = new GateDef();
    gate.id = UUID.randomUUID();
    gate.tenantId = tenantId;
    gate.processDefinitionId = processDefinitionId;
    gate.code = code;
    gate.question = question;
    gate.sortOrder = sortOrder;
    gate.responseType = "yes_no";
    gate.continueCriterion = "affirmative";
    gate.mandatory = true;
    gate.active = true;
    return gate;
  }
}
