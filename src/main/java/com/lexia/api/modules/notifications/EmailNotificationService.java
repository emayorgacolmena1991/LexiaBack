package com.lexia.api.modules.notifications;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService {

  private final EmailClient emailClient;
  private final BrevoProperties properties;

  public EmailNotificationService(EmailClient emailClient, BrevoProperties properties) {
    this.emailClient = emailClient;
    this.properties = properties;
  }

  @Async
  public void sendUserInvitation(
      String email, String displayName, String token, String tenantName) {
    String url = properties.getFrontendUrl() + "/invitacion?token=" + token;
    emailClient.send(
        email,
        displayName,
        "Invitación a LEXIA · " + tenantName,
        EmailTemplates.userInvitation(displayName, url, tenantName));
  }

  @Async
  public void sendPasswordChanged(String email, String displayName) {
    emailClient.send(
        email, displayName, "Contraseña actualizada · LEXIA", EmailTemplates.passwordChanged(displayName));
  }

  @Async
  public void sendPasswordReset(String email, String displayName, String token) {
    String url = properties.getFrontendUrl() + "/restablecer?token=" + token;
    emailClient.send(
        email,
        displayName,
        "Restablecer contraseña · LEXIA",
        EmailTemplates.passwordReset(displayName, url));
  }

  @Async
  public void sendLoginAlert(
      String email, String displayName, String when, String ip, String userAgent) {
    emailClient.send(
        email,
        displayName,
        "Nuevo ingreso a LEXIA",
        EmailTemplates.loginAlert(displayName, when, ip, userAgent));
  }
}
