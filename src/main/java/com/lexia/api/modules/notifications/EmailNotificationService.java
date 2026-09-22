package com.lexia.api.modules.notifications;

import com.lexia.api.modules.admin.IntegrationEmailTracker;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService {

  private final EmailClient emailClient;
  private final BrevoProperties properties;
  private final Optional<IntegrationEmailTracker> emailTracker;

  public EmailNotificationService(
      EmailClient emailClient,
      BrevoProperties properties,
      @Autowired(required = false) IntegrationEmailTracker emailTracker) {
    this.emailClient = emailClient;
    this.properties = properties;
    this.emailTracker = Optional.ofNullable(emailTracker);
  }

  @Async
  public void sendUserInvitation(
      UUID tenantId, String email, String displayName, String token, String tenantName) {
    deliver(
        tenantId,
        email,
        "invitation",
        "invite:" + token,
        () -> {
          String url = properties.getFrontendUrl() + "/invitacion?token=" + token;
          emailClient.send(
              email,
              displayName,
              "Invitación a LEXIA · " + tenantName,
              EmailTemplates.userInvitation(displayName, url, tenantName));
        });
  }

  @Async
  public void sendPasswordChanged(UUID tenantId, String email, String displayName) {
    deliver(
        tenantId,
        email,
        "password-changed",
        "password-changed:" + email + ":" + System.currentTimeMillis() / 60_000,
        () ->
            emailClient.send(
                email,
                displayName,
                "Contraseña actualizada · LEXIA",
                EmailTemplates.passwordChanged(displayName)));
  }

  @Async
  public void sendPasswordReset(UUID tenantId, String email, String displayName, String token) {
    deliver(
        tenantId,
        email,
        "password-reset",
        "password-reset:" + token,
        () -> {
          String url = properties.getFrontendUrl() + "/restablecer?token=" + token;
          emailClient.send(
              email,
              displayName,
              "Restablecer contraseña · LEXIA",
              EmailTemplates.passwordReset(displayName, url));
        });
  }

  @Async
  public void sendLoginAlert(
      UUID tenantId,
      String email,
      String displayName,
      String when,
      String ip,
      String userAgent) {
    deliver(
        tenantId,
        email,
        "login-alert",
        "login-alert:" + email + ":" + System.currentTimeMillis() / 300_000,
        () ->
            emailClient.send(
                email,
                displayName,
                "Nuevo ingreso a LEXIA",
                EmailTemplates.loginAlert(displayName, when, ip, userAgent)));
  }

  private void deliver(UUID tenantId, String email, String purpose, String idempotencyKey, Runnable send) {
    Optional<UUID> callId =
        emailTracker.flatMap(tracker -> tracker.queue(tenantId, email, purpose, idempotencyKey));
    try {
      send.run();
      callId.ifPresent(id -> emailTracker.ifPresent(tracker -> tracker.succeed(id)));
    } catch (RuntimeException ex) {
      callId.ifPresent(
          id ->
              emailTracker.ifPresent(
                  tracker -> tracker.fail(id, ex.getMessage(), email, purpose)));
      throw ex;
    }
  }
}
