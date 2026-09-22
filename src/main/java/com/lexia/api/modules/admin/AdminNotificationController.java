package com.lexia.api.modules.admin;

import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminNotificationController {

  private final EmailNotificationService emails;
  private final AuthorizationService authorization;
  private final InAppNotificationService inAppNotifications;
  public AdminNotificationController(
      EmailNotificationService emails,
      AuthorizationService authorization,
      InAppNotificationService inAppNotifications) {
    this.emails = emails;
    this.authorization = authorization;
    this.inAppNotifications = inAppNotifications;
  }

  @PostMapping("/test-invitation")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void sendTestInvitation(@Valid @RequestBody TestEmailRequest request) {
    authorization.requirePermission("admin:integraciones:escribir");
    var principal = AuthContext.require();
    String tenantName =
        request.tenantName() == null || request.tenantName().isBlank()
            ? "Tenant Demo"
            : request.tenantName();
    emails.sendUserInvitation(
        principal.tenantId(),
        request.email(),
        request.displayName(),
        "test-" + UUID.randomUUID(),
        tenantName);
    if (principal.tenantId() != null) {
      inAppNotifications.onInvitationTestQueued(
          principal.tenantId(), principal.userId(), request.email());
    }
  }

  public record TestEmailRequest(
      @NotBlank @Email String email,
      @NotBlank String displayName,
      String tenantName) {}
}
