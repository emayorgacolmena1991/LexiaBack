package com.lexia.api.modules.expedientes.caso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "case_action")
public class CaseAction {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(nullable = false, length = 240)
  private String title;

  @Column(name = "action_type", length = 64)
  private String actionType;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "acted_at")
  private Instant actedAt;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static CaseAction create(
      UUID tenantId,
      UUID caseId,
      String title,
      String actionType,
      String status,
      UUID actorUserId,
      Instant actedAt) {
    CaseAction action = new CaseAction();
    action.id = UUID.randomUUID();
    action.tenantId = tenantId;
    action.caseId = caseId;
    action.title = title;
    action.actionType = actionType;
    action.status = status;
    action.actorUserId = actorUserId;
    action.actedAt = actedAt;
    action.createdAt = Instant.now();
    return action;
  }

  public UUID getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public String getActionType() {
    return actionType;
  }

  public String getStatus() {
    return status;
  }

  public Instant getActedAt() {
    return actedAt;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
