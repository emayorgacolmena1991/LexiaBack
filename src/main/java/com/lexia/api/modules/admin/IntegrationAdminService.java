package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.notifications.InAppNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class IntegrationAdminService {

  private static final DateTimeFormatter LABEL =
      DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.forLanguageTag("es-CO"))
          .withZone(ZoneOffset.UTC);

  private final IntegrationRepository integrations;
  private final IntegrationCallRepository integrationCalls;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final BrevoProperties brevoProperties;
  private final InAppNotificationService inAppNotifications;

  public IntegrationAdminService(
      IntegrationRepository integrations,
      IntegrationCallRepository integrationCalls,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      BrevoProperties brevoProperties,
      InAppNotificationService inAppNotifications) {
    this.integrations = integrations;
    this.integrationCalls = integrationCalls;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.brevoProperties = brevoProperties;
    this.inAppNotifications = inAppNotifications;
  }

  @Transactional(readOnly = true)
  public AdminDtos.IntegrationsOverview getOverview() {
    requireReadAccess();
    UUID tenantId = AuthContext.require().tenantId();
    List<AdminDtos.IntegrationItem> connectors =
        integrations.findByTenantIdOrderByNameAsc(tenantId).stream()
            .map(this::toItem)
            .toList();
    Integration emailIntegration =
        integrations.findByTenantIdAndCode(tenantId, "EMAIL").orElse(null);
    AdminDtos.EmailChannelStatus email = buildEmailStatus(emailIntegration, tenantId);
    long enabledCount = connectors.stream().filter(AdminDtos.IntegrationItem::enabled).count();
    return new AdminDtos.IntegrationsOverview(connectors, email, enabledCount, connectors.size());
  }

  @Transactional
  public AdminDtos.IntegrationItem updateEnabled(UUID integrationId, boolean enabled) {
    requireWriteAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Integration integration =
        integrations
            .findByIdAndTenantId(integrationId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "NOT_FOUND", "Integración no encontrada."));
    if (integration.isEnabled() == enabled) {
      return toItem(integration);
    }
    integration.setEnabled(enabled);
    integrations.save(integration);
    audit(
        enabled ? "admin.integration.enabled" : "admin.integration.disabled",
        "integration",
        integration.getId(),
        "OK");
    inAppNotifications.notifyTenantAdmins(
        tenantId,
        AuthContext.require().userId(),
        "INTEGRATION_TOGGLED",
        Map.of(
            "name", integration.getName(),
            "state", enabled ? "activada" : "desactivada"),
        "/administracion?seccion=integraciones",
        "integration-toggle:" + integration.getId() + ":" + enabled);
    return toItem(integration);
  }

  private AdminDtos.IntegrationItem toItem(Integration integration) {
    IntegrationCall lastCall =
        integrationCalls
            .findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
                integration.getId(), integration.getTenantId())
            .orElse(null);
    return new AdminDtos.IntegrationItem(
        integration.getId(),
        integration.getCode(),
        integration.getName(),
        integration.isEnabled(),
        mapCallStatus(lastCall),
        lastCall != null ? LABEL.format(lastCall.getCreatedAt()) : null,
        true);
  }

  private AdminDtos.EmailChannelStatus buildEmailStatus(
      Integration emailIntegration, UUID tenantId) {
    IntegrationCall lastCall =
        emailIntegration == null
            ? null
            : integrationCalls
                .findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
                    emailIntegration.getId(), tenantId)
                .orElse(null);
    return new AdminDtos.EmailChannelStatus(
        brevoProperties.isEnabled(),
        brevoProperties.getApiKey() != null && !brevoProperties.getApiKey().isBlank(),
        brevoProperties.getSenderEmail(),
        emailIntegration != null && emailIntegration.isEnabled(),
        lastCall != null ? mapCallStatus(lastCall) : "NONE",
        lastCall != null ? LABEL.format(lastCall.getCreatedAt()) : null);
  }

  private static String mapCallStatus(IntegrationCall call) {
    if (call == null) {
      return "NONE";
    }
    return switch (call.getStatus()) {
      case "SUCCEEDED" -> "SUCCEEDED";
      case "FAILED" -> "FAILED";
      case "QUEUED" -> "QUEUED";
      case "LOGGED" -> "LOGGED";
      default -> "PENDING";
    };
  }

  private void requireReadAccess() {
    if (authorization.hasPermission("admin:integraciones:escribir")) {
      return;
    }
    authorization.requirePermission("admin:integraciones:escribir");
  }

  private void requireWriteAccess() {
    authorization.requirePermission("admin:integraciones:escribir");
  }

  private void audit(String event, String objectType, UUID objectId, String result) {
    AuthPrincipal principal = AuthContext.get();
    UUID tenantId = principal != null ? principal.tenantId() : null;
    UUID actor = principal != null ? principal.userId() : null;
    HttpServletRequest request = currentRequest();
    String ip = request != null ? clientIp(request) : null;
    String userAgent = request != null ? request.getHeader("User-Agent") : null;
    auditEvents.save(AuditEvent.of(tenantId, actor, event, objectType, objectId, result, ip, userAgent));
  }

  private static HttpServletRequest currentRequest() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
      return servletRequestAttributes.getRequest();
    }
    return null;
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
