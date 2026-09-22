package com.lexia.api.modules.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.UserInvitation;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.notifications.NotificationDtos.NotificationItem;
import com.lexia.api.modules.notifications.NotificationDtos.NotificationListResponse;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class InAppNotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(InAppNotificationService.class);
  private static final String CATEGORY_ADMIN = "ADMIN";
  private static final Duration SECURITY_DIGEST_WINDOW = Duration.ofHours(24);
  private static final DateTimeFormatter RELATIVE_ANCHOR =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("es-CO"))
          .withZone(ZoneOffset.UTC);

  private final UserNotificationRepository notifications;
  private final MembershipRepository memberships;
  private final UserInvitationRepository invitations;
  private final TenantRepository tenants;
  private final AuditEventRepository auditEvents;
  private final AppUserRepository appUsers;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public InAppNotificationService(
      UserNotificationRepository notifications,
      MembershipRepository memberships,
      UserInvitationRepository invitations,
      TenantRepository tenants,
      AuditEventRepository auditEvents,
      AppUserRepository appUsers,
      AuthorizationService authorization,
      ObjectMapper objectMapper) {
    this.notifications = notifications;
    this.memberships = memberships;
    this.invitations = invitations;
    this.tenants = tenants;
    this.auditEvents = auditEvents;
    this.appUsers = appUsers;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public NotificationListResponse listAdminNotifications() {
    AuthPrincipal principal = requireSession();
    if (!hasAdminInboxAccess()) {
      return new NotificationListResponse(List.of(), 0);
    }
    syncAdminDigest(principal);
    UUID tenantId = principal.tenantId();
    UUID userId = principal.userId();
    List<NotificationItem> items =
        notifications
            .findTop50ByTenantIdAndUserIdAndCategoryOrderByCreatedAtDesc(
                tenantId, userId, CATEGORY_ADMIN)
            .stream()
            .map(this::toItem)
            .toList();
    long unread =
        notifications.countByTenantIdAndUserIdAndCategoryAndReadAtIsNull(
            tenantId, userId, CATEGORY_ADMIN);
    return new NotificationListResponse(items, unread);
  }

  @Transactional
  public void syncAdminDigest(AuthPrincipal principal) {
    if (principal.tenantId() == null || !hasAdminInboxAccess()) {
      return;
    }
    UUID tenantId = principal.tenantId();
    UUID userId = principal.userId();
    digestExpiringInvitations(tenantId, userId);
    digestTenantUserLimit(tenantId, userId);
    digestSecurityAlerts(tenantId, userId);
  }

  @Transactional
  public void markRead(UUID notificationId) {
    AuthPrincipal principal = requireSession();
    UserNotification notification =
        notifications
            .findById(notificationId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "NOT_FOUND", "Notificación no encontrada."));
    assertOwner(notification, principal);
    notification.markRead();
    notifications.save(notification);
  }

  @Transactional
  public void markAllRead(String category) {
    AuthPrincipal principal = requireSession();
    String resolved = category == null || category.isBlank() ? CATEGORY_ADMIN : category.trim();
    notifications.markAllRead(principal.tenantId(), principal.userId(), resolved);
  }

  @Transactional
  public void notifyUser(
      UUID tenantId,
      UUID userId,
      String type,
      Map<String, String> params,
      String href,
      String dedupeKey) {
    if (tenantId == null || userId == null) {
      return;
    }
    if (dedupeKey != null && !dedupeKey.isBlank()) {
      boolean exists =
          notifications
              .findByTenantIdAndUserIdAndDedupeKey(tenantId, userId, dedupeKey)
              .isPresent();
      if (exists) {
        return;
      }
    }
    try {
      notifications.save(
          UserNotification.create(
              tenantId,
              userId,
              CATEGORY_ADMIN,
              type,
              writeParams(params),
              href,
              dedupeKey));
      notifications.flush();
    } catch (DataIntegrityViolationException ex) {
      LOG.debug("Notificación duplicada omitida: {}", dedupeKey);
    }
  }

  @Transactional
  public void notifyTenantAdmins(
      UUID tenantId,
      UUID excludeUserId,
      String type,
      Map<String, String> params,
      String href,
      String dedupeKeyPrefix) {
    for (UUID userId : adminUserIds(tenantId)) {
      if (excludeUserId != null && excludeUserId.equals(userId)) {
        continue;
      }
      String dedupe = dedupeKeyPrefix == null ? null : dedupeKeyPrefix + ":" + userId;
      notifyUser(tenantId, userId, type, params, href, dedupe);
    }
  }

  @Transactional
  public void onInvitationSent(UUID tenantId, UUID actorUserId, String email, UUID invitationId) {
    notifyUser(
        tenantId,
        actorUserId,
        "INVITE_SENT",
        Map.of("email", email),
        "/administracion?seccion=usuarios",
        "invite-sent:" + invitationId + ":" + actorUserId);
  }

  @Transactional
  public void onEmailDeliveryFailed(
      UUID tenantId, String email, String purpose, String error) {
    notifyTenantAdmins(
        tenantId,
        null,
        "EMAIL_DELIVERY_FAILED",
        Map.of("email", email, "purpose", purpose, "error", error),
        "/administracion?seccion=integraciones",
        "email-failed:" + email + ":" + Instant.now().getEpochSecond() / 300);
  }

  @Transactional
  public void onInvitationTestQueued(UUID tenantId, UUID actorUserId, String email) {
    notifyUser(
        tenantId,
        actorUserId,
        "EMAIL_TEST_QUEUED",
        Map.of("email", email),
        "/administracion?seccion=integraciones",
        "email-test:" + actorUserId + ":" + Instant.now().getEpochSecond() / 300);
  }

  @Transactional
  public void onTenantUpdated(UUID tenantId, UUID actorUserId, String tenantName) {
    notifyTenantAdmins(
        tenantId,
        actorUserId,
        "TENANT_CONFIG_UPDATED",
        Map.of("tenant", tenantName),
        "/administracion?seccion=parametros",
        "tenant-updated:" + tenantId + ":" + Instant.now().getEpochSecond() / 60);
  }

  @Transactional
  public void onSecurityUpdated(UUID tenantId, UUID actorUserId) {
    notifyTenantAdmins(
        tenantId,
        actorUserId,
        "TENANT_SECURITY_UPDATED",
        Map.of(),
        "/administracion?seccion=seguridad",
        "tenant-security:" + tenantId + ":" + Instant.now().getEpochSecond() / 60);
  }

  @Transactional
  public void onUserSuspended(UUID tenantId, UUID actorUserId, String displayName) {
    notifyTenantAdmins(
        tenantId,
        actorUserId,
        "USER_SUSPENDED",
        Map.of("name", displayName),
        "/administracion?seccion=usuarios",
        "user-suspended:" + displayName + ":" + Instant.now().getEpochSecond() / 300);
  }

  @Transactional
  public void onUserUnlocked(UUID tenantId, UUID actorUserId, String displayName) {
    notifyUser(
        tenantId,
        actorUserId,
        "USER_UNLOCKED",
        Map.of("name", displayName),
        "/administracion?seccion=usuarios",
        "user-unlocked:" + displayName + ":" + actorUserId);
  }

  @Transactional
  public void onAccountLocked(UUID tenantId, String displayName, String email) {
    notifyTenantAdmins(
        tenantId,
        null,
        "ACCOUNT_LOCKED",
        Map.of("name", displayName, "email", email),
        "/administracion?seccion=usuarios&access=locked",
        "account-locked:" + email + ":" + Instant.now().getEpochSecond() / 300);
  }

  private void digestExpiringInvitations(UUID tenantId, UUID userId) {
    Instant now = Instant.now();
    Instant threshold = now.plus(Duration.ofHours(24));
    for (UserInvitation invitation : invitations.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId)) {
      if (invitation.getAcceptedAt() != null) {
        continue;
      }
      Instant expiresAt = invitation.getExpiresAt();
      if (expiresAt == null || expiresAt.isAfter(threshold) || expiresAt.isBefore(now)) {
        continue;
      }
      notifyUser(
          tenantId,
          userId,
          "INVITE_EXPIRING",
          Map.of("email", invitation.getEmail(), "hours", "24"),
          "/administracion?seccion=usuarios",
          "invite-expiring:" + invitation.getId() + ":" + userId);
    }
  }

  private void digestTenantUserLimit(UUID tenantId, UUID userId) {
    Tenant tenant = tenants.findById(tenantId).orElse(null);
    if (tenant == null || tenant.getMaxUsers() == null || tenant.getMaxUsers() <= 0) {
      return;
    }
    long seats =
        memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            tenantId, List.of("ACTIVE", "SUSPENDED", "INVITED"));
    int max = tenant.getMaxUsers();
    if (seats < Math.ceil(max * 0.9)) {
      return;
    }
    notifyUser(
        tenantId,
        userId,
        seats >= max ? "TENANT_USER_LIMIT_REACHED" : "TENANT_USER_LIMIT_WARNING",
        Map.of("used", String.valueOf(seats), "max", String.valueOf(max)),
        "/administracion?seccion=parametros",
        "tenant-limit:" + tenantId + ":" + max + ":" + seats);
  }

  private void digestSecurityAlerts(UUID tenantId, UUID userId) {
    Instant since = Instant.now().minus(SECURITY_DIGEST_WINDOW);
    List<AuditEvent> denied =
        auditEvents.findRecentDeniedByTenant(tenantId, since, PageRequest.of(0, 30));
    for (AuditEvent event : denied) {
      String type = event.getEvent().startsWith("LOGIN_FAILED:") ? "LOGIN_FAILED" : "ACCESS_DENIED";
      Map<String, String> params = securityParams(event);
      notifyUser(
          tenantId,
          userId,
          type,
          params,
          "/administracion?seccion=auditoria&result=DENIED",
          "security:" + event.getId() + ":" + userId);
    }
  }

  private Map<String, String> securityParams(AuditEvent event) {
    Map<String, String> params = new LinkedHashMap<>();
    if (event.getEvent().startsWith("LOGIN_FAILED:")) {
      params.put("reason", event.getEvent().substring("LOGIN_FAILED:".length()));
    } else if (event.getEvent().startsWith("ACCESS_DENIED:")) {
      params.put("permission", event.getEvent().substring("ACCESS_DENIED:".length()));
    } else {
      params.put("event", event.getEvent());
    }
    params.put(
        "ip",
        event.getIpAddress() == null || event.getIpAddress().isBlank()
            ? "desconocida"
            : event.getIpAddress());
    if (event.getActorUserId() != null) {
      appUsers
          .findById(event.getActorUserId())
          .map(AppUser::getEmail)
          .ifPresent(email -> params.put("email", email));
    }
    return params;
  }

  private List<UUID> adminUserIds(UUID tenantId) {
    return memberships.findAdminNotifierUserIds(tenantId);
  }

  private boolean hasAdminInboxAccess() {
    return authorization.hasPermission("admin:usuarios:invitar")
        || authorization.hasPermission("admin:tenant:leer")
        || authorization.hasPermission("admin:tenant:escribir")
        || authorization.hasPermission("admin:integraciones:escribir")
        || authorization.hasPermission("auditoria:evento:leer");
  }

  private NotificationItem toItem(UserNotification notification) {
    return new NotificationItem(
        notification.getId(),
        notification.getCategory(),
        notification.getType(),
        readParams(notification.getParams()),
        notification.getHref(),
        formatRelative(notification.getCreatedAt()),
        notification.getReadAt() == null);
  }

  private Map<String, String> readParams(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException ex) {
      return Map.of();
    }
  }

  private String writeParams(Map<String, String> params) {
    try {
      return objectMapper.writeValueAsString(params == null ? Map.of() : params);
    } catch (JsonProcessingException ex) {
      return "{}";
    }
  }

  private static String formatRelative(Instant instant) {
    Duration delta = Duration.between(instant, Instant.now());
    long minutes = delta.toMinutes();
    if (minutes < 1) {
      return "Ahora";
    }
    if (minutes < 60) {
      return "Hace " + minutes + " min";
    }
    long hours = delta.toHours();
    if (hours < 24) {
      return "Hace " + hours + " h";
    }
    return RELATIVE_ANCHOR.format(instant);
  }

  private static AuthPrincipal requireSession() {
    AuthPrincipal principal = AuthContext.require();
    if (principal.tenantId() == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "Selecciona un tenant.");
    }
    return principal;
  }

  private static void assertOwner(UserNotification notification, AuthPrincipal principal) {
    if (!notification.getTenantId().equals(principal.tenantId())
        || !notification.getUserId().equals(principal.userId())) {
      throw new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acceso denegado.");
    }
  }
}
