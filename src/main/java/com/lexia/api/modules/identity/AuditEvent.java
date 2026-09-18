package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "audit_event")
public class AuditEvent {

  @Id private UUID id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "actor_type", nullable = false, length = 32)
  private String actorType;

  @Column(nullable = false, length = 128)
  private String event;

  @Column(name = "object_type", nullable = false, length = 64)
  private String objectType;

  @Column(name = "object_id")
  private UUID objectId;

  @Column(nullable = false, length = 32)
  private String result;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @Column(name = "evidence_hash", length = 128)
  private String evidenceHash;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public String getEvent() {
    return event;
  }

  public static AuditEvent of(
      UUID tenantId,
      UUID actorUserId,
      String event,
      String objectType,
      UUID objectId,
      String result,
      String ipAddress,
      String userAgent) {
    AuditEvent row = new AuditEvent();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.actorUserId = actorUserId;
    row.actorType = actorUserId == null ? "SYSTEM" : "USER";
    row.event = event;
    row.objectType = objectType;
    row.objectId = objectId;
    row.result = normalizeResult(result);
    row.ipAddress = ipAddress;
    row.userAgent = userAgent;
    row.createdAt = Instant.now();
    return row;
  }

  private static String normalizeResult(String result) {
    if ("OK".equals(result) || "DENIED".equals(result) || "ERROR".equals(result)) {
      return result;
    }
    return "DENIED";
  }
}
