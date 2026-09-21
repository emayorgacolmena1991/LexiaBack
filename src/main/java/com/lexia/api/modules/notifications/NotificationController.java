package com.lexia.api.modules.notifications;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotificationController {

  private final InAppNotificationService notifications;

  public NotificationController(InAppNotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public NotificationDtos.NotificationListResponse list(
      @RequestParam(defaultValue = "ADMIN") String category) {
    if ("ADMIN".equalsIgnoreCase(category)) {
      return notifications.listAdminNotifications();
    }
    return new NotificationDtos.NotificationListResponse(java.util.List.of(), 0);
  }

  @PatchMapping("/{id}/read")
  public void markRead(@PathVariable UUID id) {
    notifications.markRead(id);
  }

  @PostMapping("/read-all")
  public void markAllRead(@RequestParam(defaultValue = "ADMIN") String category) {
    notifications.markAllRead(category);
  }
}
