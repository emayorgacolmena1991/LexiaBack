package com.lexia.api.modules.auth;

import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.MembershipRole;
import com.lexia.api.modules.identity.MembershipRoleRepository;
import com.lexia.api.modules.identity.Role;
import com.lexia.api.modules.identity.RoleRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.tenancy.TenantModule;
import com.lexia.api.modules.tenancy.TenantModuleRepository;
import com.lexia.api.modules.tenancy.TenantRepository;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AuthService {

  private static final String GENERIC_LOGIN = "Correo o contraseña no válidos.";
  private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
  private static final DateTimeFormatter LOGIN_WHEN =
      DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.forLanguageTag("es-CO"));

  private final AuthProperties properties;
  private final AppUserRepository users;
  private final UserCredentialRepository credentials;
  private final MembershipRepository memberships;
  private final MembershipRoleRepository membershipRoles;
  private final RoleRepository roles;
  private final TenantRepository tenants;
  private final TenantModuleRepository tenantModules;
  private final MfaFactorRepository factors;
  private final RecoveryCodeRepository recoveryCodes;
  private final AuthChallengeRepository challenges;
  private final UserSessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final LoginAttemptRepository attempts;
  private final AuditEventRepository auditEvents;
  private final PasswordHasher passwords;
  private final TotpService totp;
  private final SecretCipher cipher;
  private final AuthCookies cookies;
  private final TenantBinder tenantBinder;
  private final EmailNotificationService emails;
  private final InAppNotificationService inAppNotifications;
  private final AuthorizationService authorization;
  private final TenantParameterService tenantParameters;

  public AuthService(
      AuthProperties properties,
      AppUserRepository users,
      UserCredentialRepository credentials,
      MembershipRepository memberships,
      MembershipRoleRepository membershipRoles,
      RoleRepository roles,
      TenantRepository tenants,
      TenantModuleRepository tenantModules,
      MfaFactorRepository factors,
      RecoveryCodeRepository recoveryCodes,
      AuthChallengeRepository challenges,
      UserSessionRepository sessions,
      RefreshTokenRepository refreshTokens,
      LoginAttemptRepository attempts,
      AuditEventRepository auditEvents,
      PasswordHasher passwords,
      TotpService totp,
      SecretCipher cipher,
      AuthCookies cookies,
      TenantBinder tenantBinder,
      EmailNotificationService emails,
      InAppNotificationService inAppNotifications,
      AuthorizationService authorization,
      TenantParameterService tenantParameters) {
    this.properties = properties;
    this.users = users;
    this.credentials = credentials;
    this.memberships = memberships;
    this.membershipRoles = membershipRoles;
    this.roles = roles;
    this.tenants = tenants;
    this.tenantModules = tenantModules;
    this.factors = factors;
    this.recoveryCodes = recoveryCodes;
    this.challenges = challenges;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.attempts = attempts;
    this.auditEvents = auditEvents;
    this.passwords = passwords;
    this.totp = totp;
    this.cipher = cipher;
    this.cookies = cookies;
    this.tenantBinder = tenantBinder;
    this.emails = emails;
    this.inAppNotifications = inAppNotifications;
    this.authorization = authorization;
    this.tenantParameters = tenantParameters;
  }

  @Transactional(noRollbackFor = AuthException.class)
  public AuthDtos.AuthResponse login(
      AuthDtos.LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
    String email = normalize(request.email());
    String ip = clientIp(http);
    String userAgent = userAgent(http);
    rejectIfIpThrottled(ip);

    Optional<AppUser> found = users.findByEmail(email);
    if (found.isEmpty()) {
      passwords.consumeDummy();
      fail(email, ip, userAgent, null, "UNKNOWN_USER");
    }

    AppUser user = found.get();
    rejectIfLocked(user);
    if (!"ACTIVE".equals(user.getStatus())) {
      fail(email, ip, userAgent, user, "DISABLED");
    }

    UserCredential credential =
        credentials.findByUserId(user.getId()).orElse(null);
    if (credential == null || !passwords.matches(request.password(), credential.getPasswordHash())) {
      fail(email, ip, userAgent, user, "BAD_PASSWORD");
    }

    if (user.getLockedUntil() != null) {
      user.setLockedUntil(null);
    }

    enforceTenantMfaPolicy(user);

    if (mfaEnabled(user.getId())) {
      AuthChallenge challenge =
          AuthChallenge.loginMfa(
              user.getId(), Instant.now().plus(Duration.ofMinutes(properties.getChallengeMinutes())));
      challenges.save(challenge);
      attempts.save(LoginAttempt.record(email, ip, true, "MFA_REQUIRED"));
      audit(null, user.getId(), "LOGIN_MFA_CHALLENGE", "user", user.getId(), "OK", ip, userAgent);
      return new AuthDtos.AuthResponse(
          "MFA_REQUIRED",
          challenge.getId().toString(),
          user.getId(),
          user.getEmail(),
          user.getDisplayName(),
          null,
          null,
          List.of(),
          List.of(),
          true,
          null);
    }

    return completeLogin(user, ip, userAgent, response, "PASSWORD");
  }

  /** Solo disponible con {@code lexia.e2e.enabled=true}; omite MFA para automatización. */
  @Transactional(noRollbackFor = AuthException.class)
  public AuthDtos.AuthResponse e2eLogin(
      AuthDtos.LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
    String email = normalize(request.email());
    String ip = clientIp(http);
    String userAgent = userAgent(http);

    AppUser user =
        users
            .findByEmail(email)
            .orElseThrow(() -> AuthException.unauthorized("Credenciales no válidas."));

    UserCredential credential = credentials.findByUserId(user.getId()).orElse(null);
    if (credential == null || !passwords.matches(request.password(), credential.getPasswordHash())) {
      throw AuthException.unauthorized("Credenciales no válidas.");
    }

    if (user.getLockedUntil() != null) {
      user.setLockedUntil(null);
    }
    if ("LOCKED".equals(user.getStatus())) {
      user.setStatus("ACTIVE");
    }
    if (!"ACTIVE".equals(user.getStatus())) {
      throw AuthException.unauthorized("Credenciales no válidas.");
    }

    return completeLogin(user, ip, userAgent, response, "E2E");
  }

  @Transactional(noRollbackFor = AuthException.class)
  public AuthDtos.AuthResponse verifyMfa(
      AuthDtos.MfaVerifyRequest request, HttpServletRequest http, HttpServletResponse response) {
    UUID challengeId;
    try {
      challengeId = UUID.fromString(request.challengeId());
    } catch (IllegalArgumentException ex) {
      throw AuthException.unauthorized("El código de verificación no es válido.");
    }
    AuthChallenge challenge =
        challenges.findById(challengeId).orElseThrow(() -> AuthException.unauthorized("El código de verificación no es válido."));
    if (!challenge.isUsable()) {
      throw AuthException.unauthorized("El reto de verificación venció. Vuelve a iniciar sesión.");
    }
    AppUser user =
        users.findById(challenge.getUserId()).orElseThrow(() -> AuthException.unauthorized("El código de verificación no es válido."));
    String ip = clientIp(http);
    rejectIfLocked(user);
    if (!matchesMfa(user.getId(), request.code())) {
      fail(user.getEmail(), ip, userAgent(http), user, "BAD_MFA");
    }
    challenge.consume();
    return completeLogin(user, ip, userAgent(http), response, "MFA");
  }

  @Transactional
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    AuthPrincipal principal = AuthContext.get();
    if (principal != null) {
      sessions.findById(principal.sessionId()).ifPresent(UserSession::revoke);
      refreshTokens.findBySessionId(principal.sessionId()).forEach(RefreshToken::revoke);
      audit(
          principal.tenantId(),
          principal.userId(),
          "LOGOUT",
          "session",
          principal.sessionId(),
          "OK",
          clientIp(request),
          userAgent(request));
    }
    cookies.clear(response);
  }

  @Transactional
  public AuthDtos.AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
    String raw = CookieReader.get(request, AuthCookies.REFRESH);
    if (raw == null || raw.isBlank()) {
      throw AuthException.unauthorized("Sesión no válida o vencida.");
    }
    RefreshToken current =
        refreshTokens
            .findByTokenHash(TokenHasher.sha256(raw))
            .orElseThrow(() -> AuthException.unauthorized("Sesión no válida o vencida."));
    if (current.getRevokedAt() != null) {
      refreshTokens.findByFamilyId(current.getFamilyId()).forEach(RefreshToken::revoke);
      throw AuthException.unauthorized("La sesión fue cerrada por seguridad.");
    }
    if (current.getExpiresAt().isBefore(Instant.now())) {
      throw AuthException.unauthorized("Sesión no válida o vencida.");
    }
    UserSession session =
        sessions
            .findById(current.getSessionId())
            .orElseThrow(() -> AuthException.unauthorized("Sesión no válida o vencida."));
    if (!session.isActive()) {
      throw AuthException.unauthorized("Sesión no válida o vencida.");
    }
    String nextRaw = TokenHasher.randomToken();
    RefreshToken next =
        RefreshToken.issue(
            session.getId(),
            TokenHasher.sha256(nextRaw),
            current.getFamilyId(),
            Instant.now().plus(Duration.ofDays(properties.getRefreshDays())));
    refreshTokens.save(next);
    current.revoke(next.getId());
    cookies.write(response, session.getId().toString(), nextRaw);
    if (session.getTenantId() != null) {
      tenantBinder.bind(session.getTenantId());
    }
    AppUser user = users.findById(session.getUserId()).orElseThrow();
    return toAuthResponse(user, session, "AUTHENTICATED");
  }

  @Transactional(readOnly = true)
  public AuthDtos.SessionResponse me() {
    AuthPrincipal principal = AuthContext.require();
    AppUser user = users.findById(principal.userId()).orElseThrow();
    Tenant tenant = tenants.findById(principal.tenantId()).orElse(null);
    return toSessionResponse(
        user, principal.tenantId(), tenant, principal.membershipId(), principal.sessionId());
  }

  @Transactional
  public AuthDtos.SessionResponse updateProfile(AuthDtos.ProfileUpdateRequest request) {
    AuthPrincipal principal = AuthContext.require();
    AppUser user = users.findById(principal.userId()).orElseThrow();
    user.updateDisplayName(request.displayName());
    users.save(user);
    audit(
        principal.tenantId(),
        user.getId(),
        "PROFILE_UPDATE",
        "user",
        user.getId(),
        "OK",
        null,
        null);
    Tenant tenant = principal.tenantId() == null ? null : tenants.findById(principal.tenantId()).orElse(null);
    return toSessionResponse(
        user, principal.tenantId(), tenant, principal.membershipId(), principal.sessionId());
  }

  @Transactional
  public AuthDtos.UserPreferencesRequest updatePreferences(AuthDtos.UserPreferencesRequest request) {
    AuthPrincipal principal = AuthContext.require();
    AppUser user = users.findById(principal.userId()).orElseThrow();
    String locale = request.locale().trim().toLowerCase();
    String theme = request.theme().trim().toLowerCase();
    if (!locale.equals("es") && !locale.equals("en")) {
      throw AuthException.badRequest("Idioma no soportado.");
    }
    if (!theme.equals("light") && !theme.equals("dark") && !theme.equals("system")) {
      throw AuthException.badRequest("Tema no soportado.");
    }
    user.updatePreferences(locale, request.timezone(), theme);
    users.save(user);
    audit(
        principal.tenantId(),
        user.getId(),
        "PREFERENCES_UPDATE",
        "user",
        user.getId(),
        "OK",
        null,
        null);
    return new AuthDtos.UserPreferencesRequest(user.getLocale(), user.getTimezone(), user.getTheme());
  }

  private AuthDtos.SessionResponse toSessionResponse(
      AppUser user, UUID tenantId, Tenant tenant, UUID membershipId, UUID sessionId) {
    return new AuthDtos.SessionResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        tenantId,
        tenant == null ? null : tenant.getName(),
        roleCodes(membershipId),
        permissionCodes(membershipId),
        tenantModuleItems(tenantId),
        mfaEnabled(user.getId()),
        user.isNotifyOnLogin(),
        user.getLocale(),
        user.getTimezone(),
        user.getTheme(),
        sessionId == null ? null : sessionId.toString());
  }

  private List<AuthDtos.TenantModuleItem> tenantModuleItems(UUID tenantId) {
    if (tenantId == null) {
      return List.of();
    }
    return tenantModules.findByTenantIdAndEnabledTrueOrderByModuleCodeAsc(tenantId).stream()
        .map(module -> new AuthDtos.TenantModuleItem(module.getModuleCode(), module.isEnabled()))
        .toList();
  }

  @Transactional(readOnly = true)
  public AuthDtos.MfaStatusResponse mfaStatus() {
    AuthPrincipal principal = AuthContext.require();
    return new AuthDtos.MfaStatusResponse(mfaEnabled(principal.userId()), "TOTP", List.of());
  }

  @Transactional
  public AuthDtos.NotificationPreferencesRequest updateNotificationPreferences(
      AuthDtos.NotificationPreferencesRequest request) {
    AuthPrincipal principal = AuthContext.require();
    AppUser user = users.findById(principal.userId()).orElseThrow();
    user.setNotifyOnLogin(request.notifyOnLogin());
    return request;
  }

  @Transactional
  public AuthDtos.MfaEnrollResponse enrollMfa() {
    AuthPrincipal principal = AuthContext.require();
    if (mfaEnabled(principal.userId())) {
      throw AuthException.conflict("El doble factor ya está activo.");
    }
    String secret = totp.newSecret();
    String encrypted = cipher.encrypt(secret);
    List<MfaFactor> existing = factors.findByUserIdAndFactorType(principal.userId(), "TOTP");
    if (existing.isEmpty()) {
      factors.save(MfaFactor.pending(principal.userId(), encrypted));
    } else {
      MfaFactor factor = existing.get(0);
      factor.setSecretEncrypted(encrypted);
      factor.setEnabled(false);
      factor.setConfirmedAt(null);
      factors.save(factor);
    }
    AppUser user = users.findById(principal.userId()).orElseThrow();
    return new AuthDtos.MfaEnrollResponse(secret, totp.otpauthUrl(user.getEmail(), secret), "LEXIA");
  }

  @Transactional
  public AuthDtos.MfaStatusResponse confirmMfa(AuthDtos.MfaConfirmRequest request) {
    AuthPrincipal principal = AuthContext.require();
    MfaFactor factor =
        factors.findByUserIdAndFactorType(principal.userId(), "TOTP").stream()
            .findFirst()
            .orElseThrow(() -> AuthException.badRequest("Primero genera el código de alta."));
    if (!totp.verify(cipher.decrypt(factor.getSecretEncrypted()), request.code())) {
      throw AuthException.unauthorized("El código de verificación no es válido.");
    }
    factor.setEnabled(true);
    factor.setConfirmedAt(Instant.now());
    recoveryCodes.deleteByUserId(principal.userId());
    List<String> codes = List.of(
        recovery(), recovery(), recovery(), recovery(), recovery(), recovery(), recovery(), recovery());
    codes.forEach(
        code -> recoveryCodes.save(RecoveryCode.of(principal.userId(), TokenHasher.sha256(normalizeRecovery(code)))));
    audit(
        principal.tenantId(),
        principal.userId(),
        "MFA_ENABLED",
        "mfa_factor",
        factor.getId(),
        "OK",
        null,
        null);
    return new AuthDtos.MfaStatusResponse(true, "TOTP", codes);
  }

  @Transactional
  public AuthDtos.MfaStatusResponse disableMfa(AuthDtos.MfaConfirmRequest request) {
    AuthPrincipal principal = AuthContext.require();
    if (!matchesMfa(principal.userId(), request.code())) {
      throw AuthException.unauthorized("El código de verificación no es válido.");
    }
    factors.findByUserIdAndFactorType(principal.userId(), "TOTP").forEach(factor -> {
      factor.setEnabled(false);
      factor.setConfirmedAt(null);
    });
    recoveryCodes.deleteByUserId(principal.userId());
    audit(
        principal.tenantId(),
        principal.userId(),
        "MFA_DISABLED",
        "user",
        principal.userId(),
        "OK",
        null,
        null);
    return new AuthDtos.MfaStatusResponse(false, "TOTP", List.of());
  }

  private AuthDtos.AuthResponse completeLogin(
      AppUser user, String ip, String userAgent, HttpServletResponse response, String method) {
    List<Membership> active = memberships.findActiveForLogin(user.getId());
    if (active.isEmpty()) {
      throw AuthException.unauthorized("No hay una membresía activa para este usuario.");
    }
    Membership membership = active.get(0);
    tenantBinder.bind(membership.getTenantId());
    Instant expiresAt = Instant.now().plus(Duration.ofHours(properties.getSessionHours()));
    UserSession session =
        sessions.save(
            UserSession.open(
                user.getId(),
                membership.getTenantId(),
                membership.getId(),
                ip,
                userAgent,
                expiresAt));
    String refreshRaw = TokenHasher.randomToken();
    refreshTokens.save(
        RefreshToken.issue(
            session.getId(),
            TokenHasher.sha256(refreshRaw),
            UUID.randomUUID(),
            Instant.now().plus(Duration.ofDays(properties.getRefreshDays()))));
    cookies.write(response, session.getId().toString(), refreshRaw);
    attempts.save(LoginAttempt.record(user.getEmail(), ip, true, method));
    audit(membership.getTenantId(), user.getId(), "LOGIN", "session", session.getId(), "OK", ip, userAgent);
    if (user.isNotifyOnLogin()) {
      emails.sendLoginAlert(
          membership.getTenantId(),
          user.getEmail(),
          user.getDisplayName(),
          LOGIN_WHEN.format(Instant.now().atZone(BOGOTA)),
          ip == null ? "Desconocida" : ip,
          userAgent == null || userAgent.isBlank() ? "Desconocido" : userAgent);
    }
    return toAuthResponse(user, session, "AUTHENTICATED");
  }

  private AuthDtos.AuthResponse toAuthResponse(AppUser user, UserSession session, String status) {
    Tenant tenant = session.getTenantId() == null ? null : tenants.findById(session.getTenantId()).orElse(null);
    return new AuthDtos.AuthResponse(
        status,
        null,
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        session.getTenantId(),
        tenant == null ? null : tenant.getName(),
        roleCodes(session.getMembershipId()),
        permissionCodes(session.getMembershipId()),
        mfaEnabled(user.getId()),
        session.getId().toString());
  }

  private List<String> permissionCodes(UUID membershipId) {
    return authorization.permissionsForMembership(membershipId);
  }

  private List<String> roleCodes(UUID membershipId) {
    if (membershipId == null) {
      return List.of();
    }
    return membershipRoles.findByMembershipId(membershipId).stream()
        .map(MembershipRole::getRoleId)
        .map(roles::findById)
        .flatMap(Optional::stream)
        .map(Role::getCode)
        .toList();
  }

  private void enforceTenantMfaPolicy(AppUser user) {
    if (mfaEnabled(user.getId())) {
      return;
    }
    List<Membership> active = memberships.findActiveForLogin(user.getId());
    if (active.isEmpty()) {
      return;
    }
    UUID tenantId = active.get(0).getTenantId();
    if (tenantParameters.isMfaRequired(tenantId)) {
      throw new AuthException(
          HttpStatus.FORBIDDEN,
          "MFA_SETUP_REQUIRED",
          "Tu organización exige doble factor. Configúralo en Seguridad antes de continuar.");
    }
  }

  private boolean mfaEnabled(UUID userId) {
    return factors.findByUserIdAndFactorTypeAndEnabledTrue(userId, "TOTP").isPresent();
  }

  private boolean matchesMfa(UUID userId, String code) {
    Optional<MfaFactor> factor = factors.findByUserIdAndFactorTypeAndEnabledTrue(userId, "TOTP");
    if (factor.isEmpty()) {
      return false;
    }
    if (totp.verify(cipher.decrypt(factor.get().getSecretEncrypted()), code)) {
      return true;
    }
    String hash = TokenHasher.sha256(normalizeRecovery(code));
    return recoveryCodes.findByUserIdAndUsedAtIsNull(userId).stream()
        .filter(item -> item.getCodeHash().equals(hash))
        .findFirst()
        .map(
            item -> {
              item.setUsedAt(Instant.now());
              return true;
            })
        .orElse(false);
  }

  private void rejectIfLocked(AppUser user) {
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
      throw AuthException.locked("La cuenta está bloqueada temporalmente. Inténtalo más tarde.");
    }
    if (user.getLockedUntil() != null && user.getLockedUntil().isBefore(Instant.now()) && "LOCKED".equals(user.getStatus())) {
      user.setStatus("ACTIVE");
      user.setLockedUntil(null);
    }
  }

  private void rejectIfIpThrottled(String ip) {
    long fails =
        attempts.countByIpAddressAndCreatedAtAfterAndSuccessFalse(
            ip, Instant.now().minus(Duration.ofMinutes(15)));
    if (fails >= 20) {
      throw AuthException.tooMany("Demasiados intentos. Espera unos minutos e inténtalo de nuevo.");
    }
  }

  private void fail(String email, String ip, String userAgent, AppUser user, String reason) {
    attempts.save(LoginAttempt.record(email, ip, false, reason));
    if (user != null) {
      boolean locked = lockIfNeeded(user);
      UUID tenantId = tenantIdForUser(user);
      if (tenantId != null) {
        tenantBinder.bind(tenantId);
      }
      audit(tenantId, user.getId(), "LOGIN_FAILED:" + reason, "user", user.getId(), "DENIED", ip, userAgent);
      if (locked && tenantId != null) {
        inAppNotifications.onAccountLocked(tenantId, user.getDisplayName(), user.getEmail());
      }
    } else {
      audit(null, null, "LOGIN_FAILED:" + reason, "user", null, "DENIED", ip, userAgent);
    }
    throw AuthException.unauthorized(GENERIC_LOGIN);
  }

  private boolean lockIfNeeded(AppUser user) {
    List<LoginAttempt> recent = attempts.findTop20ByEmailOrderByCreatedAtDesc(user.getEmail());
    int consecutive = 0;
    for (LoginAttempt attempt : recent) {
      if (attempt.isSuccess()) {
        break;
      }
      consecutive++;
    }
    if (consecutive >= properties.getMaxFailedAttempts()) {
      user.setStatus("LOCKED");
      user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(properties.getLockoutMinutes())));
      return true;
    }
    return false;
  }

  private UUID tenantIdForUser(AppUser user) {
    List<Membership> active = memberships.findActiveForLogin(user.getId());
    return active.isEmpty() ? null : active.get(0).getTenantId();
  }

  private void audit(
      UUID tenantId,
      UUID actor,
      String event,
      String objectType,
      UUID objectId,
      String result,
      String ip,
      String userAgent) {
    auditEvents.save(AuditEvent.of(tenantId, actor, event, objectType, objectId, result, ip, userAgent));
  }

  private static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private static String userAgent(HttpServletRequest request) {
    String value = request.getHeader("User-Agent");
    if (value == null) {
      return "";
    }
    return value.length() > 512 ? value.substring(0, 512) : value;
  }

  private static String normalizeRecovery(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
  }

  private static String recovery() {
    String token = TokenHasher.randomToken().replace("-", "").toUpperCase(Locale.ROOT);
    return token.substring(0, 4) + "-" + token.substring(4, 8);
  }
}
