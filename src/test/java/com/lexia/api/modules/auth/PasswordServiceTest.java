package com.lexia.api.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.UserInvitation;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.tenancy.TenantBinder;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
  private static final String RAW_TOKEN = "raw-invite-token";
  private static final String PASSWORD = "Accept-Test-2026!";

  @Mock private AppUserRepository users;
  @Mock private UserCredentialRepository credentials;
  @Mock private UserInvitationRepository invitations;
  @Mock private MembershipRepository memberships;
  @Mock private AuthChallengeRepository challenges;
  @Mock private RefreshTokenRepository refreshTokens;
  @Mock private UserSessionRepository sessions;
  @Mock private PasswordHasher hasher;
  @Mock private EmailNotificationService emails;
  @Mock private TenantBinder tenantBinder;

  @InjectMocks private PasswordService service;

  @Test
  void acceptInvitationActivatesMembershipAndCredential() {
    UserInvitation invitation =
        UserInvitation.issue(
            TENANT_ID,
            "nuevo@lexia.demo",
            "Nuevo Usuario",
            TokenHasher.sha256(RAW_TOKEN),
            USER_ID,
            MEMBERSHIP_ID,
            Instant.now().plusSeconds(3600));
    Membership membership = Membership.invited(USER_ID, TENANT_ID);
    setField(membership, "id", MEMBERSHIP_ID);
    AppUser user = AppUser.create("nuevo@lexia.demo", "Nuevo Usuario");
    setField(user, "id", USER_ID);

    when(invitations.findByTokenHash(TokenHasher.sha256(RAW_TOKEN)))
        .thenReturn(Optional.of(invitation));
    when(memberships.findById(MEMBERSHIP_ID)).thenReturn(Optional.of(membership));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(credentials.findByUserId(USER_ID)).thenReturn(Optional.empty());
    when(hasher.hash(PASSWORD)).thenReturn("hashed-password");
    when(credentials.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.acceptInvitation(RAW_TOKEN, PASSWORD);

    verify(tenantBinder).bind(TENANT_ID);
    verify(credentials).save(any(UserCredential.class));
    assertEquals("ACTIVE", membership.getStatus());
    verify(memberships).findById(MEMBERSHIP_ID);
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
