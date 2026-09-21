package com.lexia.api.modules.notifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER_ID = UUID.fromString("d1000000-0000-7000-8000-000000000001");
  private static final UUID MEMBERSHIP_ID = UUID.fromString("c1000000-0000-7000-8000-000000000001");

  @Mock private UserNotificationRepository notifications;
  @Mock private MembershipRepository memberships;
  @Mock private UserInvitationRepository invitations;
  @Mock private TenantRepository tenants;
  @Mock private AuditEventRepository auditEvents;
  @Mock private AppUserRepository appUsers;
  @Mock private AuthorizationService authorization;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private InAppNotificationService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    service =
        new InAppNotificationService(
            notifications,
            memberships,
            invitations,
            tenants,
            auditEvents,
            appUsers,
            authorization,
            objectMapper);
  }

  @AfterEach
  void clearContext() {
    AuthContext.clear();
  }

  @Test
  void syncAdminDigestCreatesSecurityAlertForDeniedLogin() {
    AuthContext.set(new AuthPrincipal(USER_ID, UUID.randomUUID(), TENANT_ID, MEMBERSHIP_ID));
    when(authorization.hasPermission(any()))
        .thenAnswer(invocation -> "auditoria:evento:leer".equals(invocation.getArgument(0)));
    when(invitations.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(TENANT_ID))
        .thenReturn(List.of());
    when(tenants.findById(TENANT_ID)).thenReturn(java.util.Optional.empty());

    AuditEvent denied =
        AuditEvent.of(
            TENANT_ID,
            USER_ID,
            "LOGIN_FAILED:BAD_PASSWORD",
            "user",
            USER_ID,
            "DENIED",
            "203.0.113.10",
            "test");
    when(auditEvents.findRecentDeniedByTenant(eq(TENANT_ID), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(denied));

    service.syncAdminDigest(AuthContext.require());

    verify(notifications)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                row ->
                    "LOGIN_FAILED".equals(row.getType())
                        && row.getTenantId().equals(TENANT_ID)
                        && row.getUserId().equals(USER_ID)));
  }

  @Test
  void notifyUserSkipsInsertWhenDedupeKeyExists() {
    String dedupe = "invite-expiring:5cc0099a-1925-4cf5-8f8f-215f5ef40736:" + USER_ID;
    when(notifications.findByTenantIdAndUserIdAndDedupeKey(TENANT_ID, USER_ID, dedupe))
        .thenReturn(Optional.of(mock(UserNotification.class)));

    service.notifyUser(
        TENANT_ID,
        USER_ID,
        "INVITE_EXPIRING",
        Map.of("email", "invitado@example.com"),
        "/administracion?seccion=usuarios",
        dedupe);

    verify(notifications, never()).save(any());
  }

  @Test
  void syncAdminDigestSkipsSecurityWhenNoAuditPermission() {
    AuthContext.set(new AuthPrincipal(USER_ID, UUID.randomUUID(), TENANT_ID, MEMBERSHIP_ID));
    when(authorization.hasPermission(any())).thenReturn(false);

    service.syncAdminDigest(AuthContext.require());

    verify(auditEvents, never())
        .findRecentDeniedByTenant(any(), any(Instant.class), any(Pageable.class));
  }
}
