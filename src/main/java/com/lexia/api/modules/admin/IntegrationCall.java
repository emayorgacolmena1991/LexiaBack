package com.lexia.api.modules.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "integration_call")
public class IntegrationCall {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "integration_id", nullable = false)
  private UUID integrationId;

  @Column(nullable = false, length = 16)
  private String direction;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "request_redacted")
  private String requestRedacted;

  @Column(name = "response_redacted")
  private String responseRedacted;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static IntegrationCall outbound(
      UUID tenantId,
      UUID integrationId,
      String idempotencyKey,
      String status,
      String requestRedacted,
      String responseRedacted) {
    IntegrationCall call = new IntegrationCall();
    call.id = UUID.randomUUID();
    call.tenantId = tenantId;
    call.integrationId = integrationId;
    call.direction = "OUTBOUND";
    call.idempotencyKey = idempotencyKey;
    call.status = status;
    call.requestRedacted = requestRedacted;
    call.responseRedacted = responseRedacted;
    call.createdAt = Instant.now();
    return call;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getIntegrationId() {
    return integrationId;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void markSucceeded(String responseRedacted) {
    status = "SUCCEEDED";
    this.responseRedacted = truncate(responseRedacted);
  }

  public void markFailed(String error) {
    status = "FAILED";
    responseRedacted = truncate(error);
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 512 ? value.substring(0, 512) : value;
  }
}
