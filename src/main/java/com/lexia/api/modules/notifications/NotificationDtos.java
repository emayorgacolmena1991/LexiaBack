package com.lexia.api.modules.notifications;

import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {

  private NotificationDtos() {}

  public record NotificationItem(
      UUID id,
      String category,
      String type,
      Map<String, String> params,
      String href,
      String createdAtLabel,
      boolean unread) {}

  public record NotificationListResponse(
      java.util.List<NotificationItem> items, long unreadCount) {}
}
