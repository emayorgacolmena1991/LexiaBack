package com.lexia.api.modules.auth;

import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.UserInvitation;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.tenancy.TenantBinder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class PasswordService {

  private static final Duration RESET_TTL = Duration.ofHours(1);

  private final AppUserRepository users;
  private final UserCredentialRepository credentials;
  private final UserInvitationRepository invitations;
  private final MembershipRepository memberships;
  private final AuthChallengeRepository challenges;
  private final RefreshTokenRepository refreshTokens;
  private final UserSessionRepository sessions;
  private final PasswordHasher hasher;
  private final EmailNotificationService emails;
  private final TenantBinder tenantBinder;

  public PasswordService(
      AppUserRepository users,
      UserCredentialRepository credentials,
      UserInvitationRepository invitations,
      MembershipRepository memberships,
      AuthChallengeRepository challenges,
      RefreshTokenRepository refreshTokens,
      UserSessionRepository sessions,
      PasswordHasher hasher,
      EmailNotificationService emails,
      TenantBinder tenantBinder) {
    this.users = users;
    this.credentials = credentials;
    this.invitations = invitations;
    this.memberships = memberships;
    this.challenges = challenges;
    this.refreshTokens = refreshTokens;
    this.sessions = sessions;
    this.hasher = hasher;
    this.emails = emails;
    this.tenantBinder = tenantBinder;
  }

  @Transactional
  public void acceptInvitation(String rawToken, String password) {
    UserInvitation invitation =
        invitations
            .findByTokenHash(TokenHasher.sha256(rawToken))
            .filter(UserInvitation::isPending)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "La invitación no es válida o venció."));

    tenantBinder.bind(invitation.getTenantId());
    Membership membership =
        memberships
            .findById(invitation.getMembershipId())
            .orElseThrow(
                () -> new AuthException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Membresía no encontrada."));

    AppUser user =
        users
            .findById(membership.getUserId())
            .orElseThrow(
                () -> new AuthException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Usuario no encontrado."));

    if (credentials.findByUserId(user.getId()).isPresent()) {
      throw new AuthException(HttpStatus.CONFLICT, "ALREADY_ACTIVE", "Esta invitación ya fue aceptada.");
    }

    PasswordPolicy.validate(password);
    credentials.save(UserCredential.create(user.getId(), hasher.hash(password), false));
    membership.setStatus("ACTIVE");
    invitation.accept();
  }

  @Transactional
  public void changePassword(String currentPassword, String newPassword) {
    var principal = AuthContext.require();
    AppUser user =
        users
            .findById(principal.userId())
            .orElseThrow(() -> AuthException.unauthorized("Sesión no válida o vencida."));
    UserCredential credential =
        credentials
            .findByUserId(user.getId())
            .orElseThrow(() -> AuthException.unauthorized("Sesión no válida o vencida."));

    if (!hasher.matches(currentPassword, credential.getPasswordHash())) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "BAD_PASSWORD", "La contraseña actual no es correcta.");
    }
    applyPasswordChange(credential, newPassword);
    emails.sendPasswordChanged(principal.tenantId(), user.getEmail(), user.getDisplayName());
  }

  @Transactional
  public void requestReset(String email) {
    String normalized = email.toLowerCase(Locale.ROOT).trim();
    Optional<AppUser> found = users.findByEmail(normalized);
    if (found.isEmpty()) {
      return;
    }
    AppUser user = found.get();
    if (credentials.findByUserId(user.getId()).isEmpty()) {
      return;
    }

    AuthChallenge challenge =
        AuthChallenge.passwordReset(user.getId(), Instant.now().plus(RESET_TTL));
    challenges.save(challenge);
    emails.sendPasswordReset(
        tenantIdForUser(user), user.getEmail(), user.getDisplayName(), challenge.getId().toString());
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    UUID challengeId;
    try {
      challengeId = UUID.fromString(token);
    } catch (IllegalArgumentException ex) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "El enlace no es válido.");
    }

    AuthChallenge challenge =
        challenges
            .findById(challengeId)
            .filter(c -> "PASSWORD_RESET".equals(c.getPurpose()))
            .filter(AuthChallenge::isUsable)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "El enlace de restablecimiento venció."));

    AppUser user =
        users
            .findById(challenge.getUserId())
            .orElseThrow(
                () -> new AuthException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Usuario no encontrado."));
    UserCredential credential =
        credentials
            .findByUserId(user.getId())
            .orElseThrow(
                () -> new AuthException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Usuario sin credencial."));

    applyPasswordChange(credential, newPassword);
    challenge.consume();
    invalidateSessions(user.getId());
    emails.sendPasswordChanged(tenantIdForUser(user), user.getEmail(), user.getDisplayName());
  }

  private UUID tenantIdForUser(AppUser user) {
    List<Membership> active = memberships.findActiveForLogin(user.getId());
    return active.isEmpty() ? null : active.get(0).getTenantId();
  }

  private void applyPasswordChange(UserCredential credential, String newPassword) {
    PasswordPolicy.validate(newPassword);
    credential.setPasswordHash(hasher.hash(newPassword));
    credential.setAlgorithm("argon2id");
    credential.setLastChangedAt(Instant.now());
    credential.setMustChange(false);
  }

  private void invalidateSessions(UUID userId) {
    sessions.findAll().stream()
        .filter(session -> userId.equals(session.getUserId()) && session.getRevokedAt() == null)
        .forEach(
            session -> {
              session.revoke();
              refreshTokens.deleteAll(refreshTokens.findBySessionId(session.getId()));
            });
  }
}
