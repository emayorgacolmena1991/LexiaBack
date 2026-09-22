package com.lexia.api.modules.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.auth.MfaFactorRepository;
import com.lexia.api.modules.auth.SessionInvalidationService;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.MembershipRoleRepository;
import com.lexia.api.modules.identity.Role;
import com.lexia.api.modules.identity.RoleRepository;
import com.lexia.api.modules.identity.UserInvitation;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID SESSION_ID = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("d1000000-0000-7000-8000-000000000001");
  private static final UUID OTHER_USER_ID = UUID.randomUUID();
  private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

  @Mock private AppUserRepository users;
  @Mock private MembershipRepository memberships;
  @Mock private MembershipRoleRepository membershipRoles;
  @Mock private RoleRepository roles;
  @Mock private UserInvitationRepository invitations;
  @Mock private TenantRepository tenants;
  @Mock private EmailNotificationService emails;
  @Mock private MfaFactorRepository mfaFactors;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;
  @Mock private SessionInvalidationService sessionInvalidation;
  @Mock private TenantParameterService tenantParameters;
  @Mock private InAppNotificationService inAppNotifications;

  @InjectMocks private UserAdminService service;

  @BeforeEach
  void setPrincipal() {
    AuthContext.set(new AuthPrincipal(ACTOR_ID, SESSION_ID, TENANT_ID, MEMBERSHIP_ID));
  }

  @AfterEach
  void clearPrincipal() {
    AuthContext.clear();
  }

  @Test
  void suspendMemberBlocksSelfAction() {
    when(authorization.hasPermission("admin:usuarios:editar")).thenReturn(true);
    Membership membership = activeMembership(ACTOR_ID);

    when(memberships.findById(MEMBERSHIP_ID)).thenReturn(Optional.of(membership));

    AuthException error =
        assertThrows(AuthException.class, () -> service.suspendMember(MEMBERSHIP_ID));

    assertEquals("SELF_SUSPEND", error.getCode());
    verify(memberships, never()).save(any());
    verify(sessionInvalidation, never()).invalidateUserSessions(any());
  }

  @Test
  void unlockMemberClearsLock() {
    when(authorization.hasPermission("admin:usuarios:editar")).thenReturn(true);
    Membership membership = activeMembership(OTHER_USER_ID);
    AppUser user = lockedUser(OTHER_USER_ID);

    when(memberships.findById(MEMBERSHIP_ID)).thenReturn(Optional.of(membership));
    when(users.findById(OTHER_USER_ID)).thenReturn(Optional.of(user));
    when(membershipRoles.findByMembershipId(MEMBERSHIP_ID)).thenReturn(List.of());
    when(mfaFactors.findByUserIdAndFactorTypeAndEnabledTrue(OTHER_USER_ID, "TOTP"))
        .thenReturn(Optional.empty());

    service.unlockMember(MEMBERSHIP_ID);

    verify(users).save(user);
    verify(auditEvents).save(any());
  }

  @Test
  void inviteUsesTenantInviteTtlHours() {
    doNothing().when(authorization).requirePermission(eq("admin:usuarios:invitar"));
    when(tenantParameters.getInviteTtlHours(TENANT_ID)).thenReturn(48);

    Tenant tenant = mock(Tenant.class);
    when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
    when(tenant.getMaxUsers()).thenReturn(null);
    when(tenant.getName()).thenReturn("Tenant Demo");

    when(users.findByEmail("nuevo@lexia.demo")).thenReturn(Optional.empty());
    when(users.save(any(AppUser.class)))
        .thenAnswer(
            inv -> {
              AppUser user = inv.getArgument(0);
              setField(user, "id", UUID.randomUUID());
              return user;
            });
    when(memberships.findFirstByUserIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT_ID)))
        .thenReturn(Optional.empty());
    when(memberships.save(any(Membership.class)))
        .thenAnswer(
            inv -> {
              Membership membership = inv.getArgument(0);
              setField(membership, "id", MEMBERSHIP_ID);
              return membership;
            });
    when(membershipRoles.findByMembershipId(MEMBERSHIP_ID)).thenReturn(List.of());
    Role analista = Role.create(TENANT_ID, "ANALISTA", "Analista", null);
    when(roles.findByTenantIdAndCode(TENANT_ID, "ANALISTA")).thenReturn(Optional.of(analista));
    when(invitations.findByTenantIdAndEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(
            TENANT_ID, "nuevo@lexia.demo"))
        .thenReturn(List.of());
    when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

    Instant before = Instant.now();
    service.invite(
        new AdminDtos.InviteUserRequest("nuevo@lexia.demo", "Nuevo Usuario", List.of("ANALISTA")));
    Instant latestExpiry = before.plus(Duration.ofHours(48).plusMinutes(1));

    ArgumentCaptor<UserInvitation> captor = ArgumentCaptor.forClass(UserInvitation.class);
    verify(invitations).save(captor.capture());
    Instant expiresAt = captor.getValue().getExpiresAt();
    assertTrue(expiresAt.isAfter(before.plus(Duration.ofHours(47))));
    assertTrue(expiresAt.isBefore(latestExpiry));
    verify(tenantParameters).getInviteTtlHours(TENANT_ID);
  }

  @Test
  void inviteEnforcesTenantUserLimit() {
    doNothing().when(authorization).requirePermission(eq("admin:usuarios:invitar"));
    Tenant tenant = mock(Tenant.class);
    when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
    when(tenant.getMaxUsers()).thenReturn(1);
    when(memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            TENANT_ID, List.of("ACTIVE", "SUSPENDED", "INVITED")))
        .thenReturn(1L);

    AdminDtos.InviteUserRequest request =
        new AdminDtos.InviteUserRequest("nuevo@empresa.com", "Nuevo Usuario", List.of("ANALISTA"));

    AuthException error = assertThrows(AuthException.class, () -> service.invite(request));

    assertEquals("TENANT_USER_LIMIT", error.getCode());
    verify(emails, never()).sendUserInvitation(any(), any(), any(), any(), any());
  }

  private Membership activeMembership(UUID userId) {
    Membership membership = Membership.invited(userId, TENANT_ID);
    setField(membership, "id", MEMBERSHIP_ID);
    membership.setStatus("ACTIVE");
    return membership;
  }

  private AppUser lockedUser(UUID userId) {
    AppUser user = AppUser.create("locked@empresa.com", "Usuario Bloqueado");
    setField(user, "id", userId);
    user.setLockedUntil(Instant.now().plusSeconds(3600));
    user.setStatus("LOCKED");
    return user;
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
