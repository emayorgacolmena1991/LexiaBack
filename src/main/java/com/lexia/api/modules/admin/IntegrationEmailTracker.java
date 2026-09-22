package com.lexia.api.modules.admin;

import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.notifications.InAppNotificationService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class IntegrationEmailTracker {

  private final IntegrationRepository integrations;
  private final IntegrationCallRepository integrationCalls;
  private final AuditEventRepository auditEvents;
  private final BrevoProperties brevoProperties;
  private final InAppNotificationService inAppNotifications;

  public IntegrationEmailTracker(
      IntegrationRepository integrations,
      IntegrationCallRepository integrationCalls,
      AuditEventRepository auditEvents,
      BrevoProperties brevoProperties,
      InAppNotificationService inAppNotifications) {
    this.integrations = integrations;
    this.integrationCalls = integrationCalls;
    this.auditEvents = auditEvents;
    this.brevoProperties = brevoProperties;
    this.inAppNotifications = inAppNotifications;
  }

  @Transactional
  public Optional<UUID> queue(
      UUID tenantId, String email, String purpose, String idempotencyKey) {
    if (tenantId == null) {
      return Optional.empty();
    }
    Integration emailIntegration =
        integrations.findByTenantIdAndCode(tenantId, "EMAIL").orElse(null);
    if (emailIntegration == null) {
      return Optional.empty();
    }
    String status = brevoProperties.isEnabled() ? "QUEUED" : "LOGGED";
    IntegrationCall call =
        IntegrationCall.outbound(
            tenantId,
            emailIntegration.getId(),
            idempotencyKey,
            status,
            purpose + ":" + email,
            brevoProperties.isEnabled() ? "brevo-async" : "logging-client");
    integrationCalls.save(call);
    return Optional.of(call.getId());
  }

  @Transactional
  public void succeed(UUID callId) {
    if (callId == null) {
      return;
    }
    integrationCalls
        .findById(callId)
        .ifPresent(
            call -> {
              call.markSucceeded(brevoProperties.isEnabled() ? "brevo" : "logging-client");
              integrationCalls.save(call);
            });
  }

  @Transactional
  public void fail(UUID callId, String error, String email, String purpose) {
    if (callId == null) {
      return;
    }
    integrationCalls
        .findById(callId)
        .ifPresent(
            call -> {
              call.markFailed(error);
              integrationCalls.save(call);
              auditEvents.save(
                  AuditEvent.of(
                      call.getTenantId(),
                      null,
                      "admin.integration.email_failed",
                      "integration_call",
                      call.getId(),
                      "ERROR",
                      null,
                      null));
              inAppNotifications.onEmailDeliveryFailed(
                  call.getTenantId(), email, purposeLabel(purpose), summarize(error));
            });
  }

  private static String purposeLabel(String purpose) {
    return switch (purpose) {
      case "invitation" -> "invitación";
      case "password-changed" -> "cambio de contraseña";
      case "password-reset" -> "restablecimiento de contraseña";
      case "login-alert" -> "alerta de ingreso";
      default -> purpose;
    };
  }

  private static String summarize(String error) {
    if (error == null || error.isBlank()) {
      return "Error desconocido";
    }
    return error.length() > 120 ? error.substring(0, 120) + "…" : error;
  }
}
