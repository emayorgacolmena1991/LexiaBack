package com.lexia.api.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.MembershipRoleRepository;
import com.lexia.api.modules.identity.RoleRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.tenancy.TenantModuleRepository;
import com.lexia.api.modules.tenancy.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final UUID USER_ID = UUID.fromString("d1000000-0000-7000-8000-000000000001");
  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @Mock private AuthProperties properties;
  @Mock private AppUserRepository users;
  @Mock private UserCredentialRepository credentials;
  @Mock private MembershipRepository memberships;
  @Mock private MembershipRoleRepository membershipRoles;
  @Mock private RoleRepository roles;
  @Mock private TenantRepository tenants;
  @Mock private TenantModuleRepository tenantModules;
  @Mock private MfaFactorRepository factors;
  @Mock private RecoveryCodeRepository recoveryCodes;
  @Mock private AuthChallengeRepository challenges;
  @Mock private UserSessionRepository sessions;
  @Mock private RefreshTokenRepository refreshTokens;
  @Mock private LoginAttemptRepository attempts;
  @Mock private AuditEventRepository auditEvents;
  @Mock private PasswordHasher passwords;
  @Mock private TotpService totp;
  @Mock private SecretCipher cipher;
  @Mock private AuthCookies cookies;
  @Mock private TenantBinder tenantBinder;
  @Mock private EmailNotificationService emails;
  @Mock private InAppNotificationService inAppNotifications;
  @Mock private AuthorizationService authorization;
  @Mock private TenantParameterService tenantParameters;

  @InjectMocks private AuthService service;

  @Test
  void loginBlockedWhenTenantRequiresMfaAndUserHasNone() {
    AppUser user = AppUser.create("laura.gomez@lexia.demo", "Laura Gómez");
    setField(user, "id", USER_ID);
    user.setStatus("ACTIVE");

    UserCredential credential = mock(UserCredential.class);
    when(credential.getPasswordHash()).thenReturn("hash");

    when(attempts.countByIpAddressAndCreatedAtAfterAndSuccessFalse(any(), any())).thenReturn(0L);
    when(users.findByEmail("laura.gomez@lexia.demo")).thenReturn(Optional.of(user));
    when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    when(passwords.matches(eq("Lexia-Demo-2026!"), eq("hash"))).thenReturn(true);
    when(factors.findByUserIdAndFactorTypeAndEnabledTrue(USER_ID, "TOTP")).thenReturn(Optional.empty());

    Membership membership = Membership.invited(USER_ID, TENANT_ID);
    membership.setStatus("ACTIVE");
    when(memberships.findActiveForLogin(USER_ID)).thenReturn(List.of(membership));
    when(tenantParameters.isMfaRequired(TENANT_ID)).thenReturn(true);

    AuthException error =
        assertThrows(
            AuthException.class,
            () ->
                service.login(
                    new AuthDtos.LoginRequest("laura.gomez@lexia.demo", "Lexia-Demo-2026!"),
                    mock(HttpServletRequest.class),
                    mock(HttpServletResponse.class)));

    assertEquals("MFA_SETUP_REQUIRED", error.getCode());
  }

  @Test
  void loginFailureBindsTenantBeforeDeniedAudit() {
    AppUser user = AppUser.create("emayorga@colmenas.ec", "Usuario");
    setField(user, "id", USER_ID);
    user.setStatus("ACTIVE");

    UserCredential credential = mock(UserCredential.class);
    when(credential.getPasswordHash()).thenReturn("hash");

    Membership membership = Membership.invited(USER_ID, TENANT_ID);
    membership.setStatus("ACTIVE");

    when(attempts.countByIpAddressAndCreatedAtAfterAndSuccessFalse(any(), any())).thenReturn(0L);
    when(users.findByEmail("emayorga@colmenas.ec")).thenReturn(Optional.of(user));
    when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    when(passwords.matches(eq("wrong"), eq("hash"))).thenReturn(false);
    when(memberships.findActiveForLogin(USER_ID)).thenReturn(List.of(membership));
    when(attempts.findTop20ByEmailOrderByCreatedAtDesc("emayorga@colmenas.ec")).thenReturn(List.of());

    assertThrows(
        AuthException.class,
        () ->
            service.login(
                new AuthDtos.LoginRequest("emayorga@colmenas.ec", "wrong"),
                mock(HttpServletRequest.class),
                mock(HttpServletResponse.class)));

    verify(tenantBinder).bind(TENANT_ID);
    verify(auditEvents).save(any());
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
