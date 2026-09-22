package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "outbox_event")
public class OutboxEvent {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "event_type", nullable = false, length = 96)
  private String eventType;

  @Column(nullable = false)
  private String payload;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static OutboxEvent create(UUID tenantId, String eventType, String payload) {
    OutboxEvent event = new OutboxEvent();
    event.id = UUID.randomUUID();
    event.tenantId = tenantId;
    event.eventType = eventType;
    event.payload = payload;
    event.createdAt = Instant.now();
    return event;
  }

  public void markPublished() {
    this.publishedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public boolean isPublished() {
    return publishedAt != null;
  }
}
