package com.lexia.api.modules.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "app", name = "user_notification")
public class UserNotification {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 16)
  private String category;

  @Column(nullable = false, length = 64)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String params;

  @Column(length = 512)
  private String href;

  @Column(name = "dedupe_key", length = 160)
  private String dedupeKey;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public static UserNotification create(
      UUID tenantId,
      UUID userId,
      String category,
      String type,
      String paramsJson,
      String href,
      String dedupeKey) {
    UserNotification notification = new UserNotification();
    notification.id = UUID.randomUUID();
    notification.tenantId = tenantId;
    notification.userId = userId;
    notification.category = category;
    notification.type = type;
    notification.params = paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson;
    notification.href = href;
    notification.dedupeKey = dedupeKey;
    notification.createdAt = Instant.now();
    return notification;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getCategory() {
    return category;
  }

  public String getType() {
    return type;
  }

  public String getParams() {
    return params;
  }

  public String getHref() {
    return href;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void markRead() {
    if (readAt == null) {
      readAt = Instant.now();
    }
  }
}
