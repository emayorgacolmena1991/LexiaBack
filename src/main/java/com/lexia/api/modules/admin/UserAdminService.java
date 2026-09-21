package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.MfaFactor;
import com.lexia.api.modules.auth.MfaFactorRepository;
import com.lexia.api.modules.auth.SessionInvalidationService;
import com.lexia.api.modules.auth.TokenHasher;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.MembershipRole;
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
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class UserAdminService {

  private static final String PERM_READ = "admin:usuarios:leer";
  private static final String PERM_INVITE = "admin:usuarios:invitar";
  private static final String PERM_EDIT = "admin:usuarios:editar";
  private static final String PERM_REVOKE = "admin:usuarios:revocar";

  private final AppUserRepository users;
  private final MembershipRepository memberships;
  private final MembershipRoleRepository membershipRoles;
  private final RoleRepository roles;
  private final UserInvitationRepository invitations;
  private final TenantRepository tenants;
  private final EmailNotificationService emails;
  private final MfaFactorRepository mfaFactors;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final SessionInvalidationService sessionInvalidation;
  private final TenantParameterService tenantParameters;
  private final InAppNotificationService inAppNotifications;

  public UserAdminService(
      AppUserRepository users,
      MembershipRepository memberships,
      MembershipRoleRepository membershipRoles,
      RoleRepository roles,
      UserInvitationRepository invitations,
      TenantRepository tenants,
      EmailNotificationService emails,
      MfaFactorRepository mfaFactors,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      SessionInvalidationService sessionInvalidation,
      TenantParameterService tenantParameters,
      InAppNotificationService inAppNotifications) {
    this.users = users;
    this.memberships = memberships;
    this.membershipRoles = membershipRoles;
    this.roles = roles;
    this.invitations = invitations;
    this.tenants = tenants;
    this.emails = emails;
    this.mfaFactors = mfaFactors;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.sessionInvalidation = sessionInvalidation;
    this.tenantParameters = tenantParameters;
    this.inAppNotifications = inAppNotifications;
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.RoleOption> listRoles() {
    requireReadAccess();
    UUID tenantId = AuthContext.require().tenantId();
    return roles.findByTenantId(tenantId).stream()
        .map(role -> new AdminDtos.RoleOption(role.getCode(), role.getName()))
        .toList();
  }

  @Transactional(readOnly = true)
  public AdminDtos.PageResponse<AdminDtos.InvitationItem> listInvitations(
      int page, int size, String query, String roleCode, String statusFilter) {
    requireReadAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Instant now = Instant.now();
    String q = query == null ? "" : query.trim().toLowerCase();
    List<UserInvitation> source =
        "REVOKED".equalsIgnoreCase(statusFilter)
            ? invitations.findByTenantIdAndRevokedAtIsNotNullOrderByRevokedAtDesc(tenantId)
            : invitations.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId);
    List<AdminDtos.InvitationItem> items =
        source.stream()
            .map(invitation -> toInvitationItem(invitation, now))
            .filter(
                item -> {
                  if (!q.isEmpty()) {
                    boolean matches =
                        item.email().toLowerCase().contains(q)
                            || item.displayName().toLowerCase().contains(q)
                            || item.roleName().toLowerCase().contains(q);
                    if (!matches) return false;
                  }
                  if (roleCode != null && !roleCode.isBlank() && !roleCode.equals(item.roleCode())) {
                    return false;
                  }
                  if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equals(item.status())) {
                    return false;
                  }
                  return true;
                })
            .toList();
    return paginate(items, page, size);
  }

  @Transactional(readOnly = true)
  public AdminDtos.PageResponse<AdminDtos.TenantMemberItem> listMembers(
      int page,
      int size,
      String query,
      String roleCode,
      String accessFilter,
      String mfaFilter) {
    requireReadAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Instant now = Instant.now();
    String q = query == null ? "" : query.trim().toLowerCase();
    List<AdminDtos.TenantMemberItem> items =
        memberships
            .findByTenantIdAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                tenantId, List.of("ACTIVE", "SUSPENDED"))
            .stream()
            .map(membership -> toMemberItem(membership, now))
            .filter(
                item -> {
                  if (!q.isEmpty()) {
                    boolean matches =
                        item.email().toLowerCase().contains(q)
                            || item.displayName().toLowerCase().contains(q)
                            || item.roleName().toLowerCase().contains(q);
                    if (!matches) return false;
                  }
                  if (roleCode != null && !roleCode.isBlank() && !roleCode.equals(item.roleCode())) {
                    return false;
                  }
                  if ("suspended".equals(accessFilter) && !"SUSPENDED".equals(item.membershipStatus())) {
                    return false;
                  }
                  if ("locked".equals(accessFilter)
                      && !("ACTIVE".equals(item.membershipStatus()) && "LOCKED".equals(item.userStatus()))) {
                    return false;
                  }
                  if ("active".equals(accessFilter)
                      && !("ACTIVE".equals(item.membershipStatus()) && "ACTIVE".equals(item.userStatus()))) {
                    return false;
                  }
                  if ("yes".equals(mfaFilter) && !item.mfaEnabled()) return false;
                  if ("no".equals(mfaFilter) && item.mfaEnabled()) return false;
                  return true;
                })
            .sorted(Comparator.comparing(AdminDtos.TenantMemberItem::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    return paginate(items, page, size);
  }

  @Transactional
  public AdminDtos.TenantMemberItem updateMemberRole(UUID membershipId, String roleCode) {
    requireEditAccess();
    var principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    Membership membership = requireActiveMembership(membershipId, tenantId);
    if (membership.getUserId().equals(principal.userId())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "SELF_ROLE_CHANGE", "No puedes cambiar tu propio rol.");
    }
    replaceRoles(tenantId, membershipId, List.of(roleCode.trim()));
    audit("admin.user.role_updated", "membership", membershipId, "OK");
    return toMemberItem(membership, Instant.now());
  }

  @Transactional
  public void suspendMember(UUID membershipId) {
    requireEditAccess();
    var principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    Membership membership = requireActiveMembership(membershipId, tenantId);
    if (membership.getUserId().equals(principal.userId())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "SELF_SUSPEND", "No puedes suspender tu propio acceso.");
    }
    membership.suspend();
    memberships.save(membership);
    sessionInvalidation.invalidateUserSessions(membership.getUserId());
    audit("admin.user.suspended", "membership", membershipId, "OK");
    users
        .findById(membership.getUserId())
        .ifPresent(
            user ->
                inAppNotifications.onUserSuspended(
                    tenantId, principal.userId(), user.getDisplayName()));
  }

  @Transactional
  public AdminDtos.TenantMemberItem resetMemberMfa(UUID membershipId) {
    requireEditAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Membership membership = requireMembership(membershipId, tenantId);
    if (membership.getUserId().equals(AuthContext.require().userId())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "SELF_MFA_RESET", "No puedes restablecer tu propio doble factor desde aquí.");
    }
    List<MfaFactor> factors =
        mfaFactors.findByUserIdAndFactorType(membership.getUserId(), "TOTP");
    if (factors.isEmpty()) {
      throw new AuthException(
          HttpStatus.CONFLICT, "MFA_NOT_CONFIGURED", "El usuario no tiene doble factor configurado.");
    }
    factors.forEach(
        factor -> {
          factor.setEnabled(false);
          factor.setConfirmedAt(null);
          mfaFactors.save(factor);
        });
    sessionInvalidation.invalidateUserSessions(membership.getUserId());
    audit("admin.user.mfa_reset", "app_user", membership.getUserId(), "OK");
    return toMemberItem(membership, Instant.now());
  }

  @Transactional
  public AdminDtos.TenantMemberItem reactivateMember(UUID membershipId) {
    requireEditAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Membership membership = requireMembership(membershipId, tenantId);
    if (!"SUSPENDED".equals(membership.getStatus())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "MEMBERSHIP_NOT_SUSPENDED", "La membresía no está suspendida.");
    }
    membership.setStatus("ACTIVE");
    memberships.save(membership);
    audit("admin.user.reactivated", "membership", membershipId, "OK");
    return toMemberItem(membership, Instant.now());
  }

  @Transactional
  public AdminDtos.TenantMemberItem unlockMember(UUID membershipId) {
    requireEditAccess();
    UUID tenantId = AuthContext.require().tenantId();
    Membership membership = requireMembership(membershipId, tenantId);
    AppUser user =
        users
            .findById(membership.getUserId())
            .orElseThrow(
                () ->
                    new AuthException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
    Instant now = Instant.now();
    if (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now)) {
      throw new AuthException(
          HttpStatus.CONFLICT, "USER_NOT_LOCKED", "El usuario no está bloqueado.");
    }
    user.setLockedUntil(null);
    if ("LOCKED".equals(user.getStatus())) {
      user.setStatus("ACTIVE");
    }
    users.save(user);
    audit("admin.user.unlocked", "app_user", user.getId(), "OK");
    inAppNotifications.onUserUnlocked(tenantId, AuthContext.require().userId(), user.getDisplayName());
    return toMemberItem(membership, now);
  }

  @Transactional
  public AdminDtos.InviteUserResponse resendInvitation(UUID invitationId) {
    requireInviteAccess();
    UUID tenantId = AuthContext.require().tenantId();
    UserInvitation invitation =
        invitations
            .findById(invitationId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitación no encontrada."));
    if (!invitation.getTenantId().equals(tenantId)) {
      throw new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "No autorizado para esta acción.");
    }
    if (invitation.getAcceptedAt() != null) {
      throw new AuthException(
          HttpStatus.CONFLICT, "INVITATION_ACCEPTED", "La invitación ya fue aceptada.");
    }
    if (invitation.getRevokedAt() != null) {
      throw new AuthException(
          HttpStatus.CONFLICT, "INVITATION_REVOKED", "La invitación fue revocada.");
    }
    Role role = resolvePrimaryRole(invitation.getMembershipId());
    if (role == null) {
      throw new AuthException(
          HttpStatus.CONFLICT, "ROLE_NOT_FOUND", "No hay un rol asociado a esta invitación.");
    }
    AdminDtos.InviteUserResponse response =
        invite(
            new AdminDtos.InviteUserRequest(
                invitation.getEmail(), invitation.getDisplayName(), List.of(role.getCode())));
    audit("admin.invitation.resent", "user_invitation", invitationId, "OK");
    return response;
  }

  @Transactional
  public void revokeInvitation(UUID invitationId) {
    requireRevokeAccess();
    UUID tenantId = AuthContext.require().tenantId();
    UserInvitation invitation =
        invitations
            .findById(invitationId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitación no encontrada."));
    if (!invitation.getTenantId().equals(tenantId)) {
      throw new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "No autorizado para esta acción.");
    }
    if (invitation.getAcceptedAt() != null) {
      throw new AuthException(
          HttpStatus.CONFLICT, "INVITATION_ACCEPTED", "La invitación ya fue aceptada.");
    }
    if (invitation.getRevokedAt() != null) {
      return;
    }
    invitation.revoke();
    invitations.save(invitation);
    audit("admin.invitation.revoked", "user_invitation", invitationId, "OK");
  }

  @Transactional
  public AdminDtos.InviteUserResponse invite(AdminDtos.InviteUserRequest request) {
    return issueInvite(request).response();
  }

  @Transactional
  public AdminDtos.E2eInviteResponse inviteForE2e(AdminDtos.InviteUserRequest request) {
    InviteIssuance issuance = issueInvite(request);
    return new AdminDtos.E2eInviteResponse(
        issuance.response().invitationId(),
        issuance.response().email(),
        issuance.rawToken(),
        issuance.response().expiresAt());
  }

  private InviteIssuance issueInvite(AdminDtos.InviteUserRequest request) {
    requireInviteAccess();
    var principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    if (tenantId == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "Selecciona un tenant.");
    }

    enforceUserLimit(tenantId);

    String email = request.email().toLowerCase().trim();
    String displayName = request.displayName().trim();

    AppUser user = resolveUser(email, displayName);
    Membership membership = resolveMembership(user.getId(), tenantId);
    replaceRoles(tenantId, membership.getId(), request.roles());
    revokePendingInvitations(tenantId, email);

    Duration inviteTtl = Duration.ofHours(tenantParameters.getInviteTtlHours(tenantId));
    String rawToken = TokenHasher.randomToken();
    UserInvitation invitation =
        invitations.save(
            UserInvitation.issue(
                tenantId,
                email,
                displayName,
                TokenHasher.sha256(rawToken),
                principal.userId(),
                membership.getId(),
                Instant.now().plus(inviteTtl)));

    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
    emails.sendUserInvitation(tenantId, email, displayName, rawToken, tenant.getName());
    audit("admin.invitation.sent", "user_invitation", invitation.getId(), "OK");
    inAppNotifications.onInvitationSent(
        tenantId, principal.userId(), email, invitation.getId());

    return new InviteIssuance(
        new AdminDtos.InviteUserResponse(invitation.getId(), email, invitation.getExpiresAt()),
        rawToken);
  }

  private record InviteIssuance(AdminDtos.InviteUserResponse response, String rawToken) {}

  private void requireReadAccess() {
    if (authorization.hasPermission(PERM_READ) || authorization.hasPermission(PERM_INVITE)) {
      return;
    }
    authorization.requirePermission(PERM_READ);
  }

  private void requireInviteAccess() {
    authorization.requirePermission(PERM_INVITE);
  }

  private void requireEditAccess() {
    if (authorization.hasPermission(PERM_EDIT) || authorization.hasPermission(PERM_INVITE)) {
      return;
    }
    authorization.requirePermission(PERM_EDIT);
  }

  private void requireRevokeAccess() {
    if (authorization.hasPermission(PERM_REVOKE) || authorization.hasPermission(PERM_INVITE)) {
      return;
    }
    authorization.requirePermission(PERM_REVOKE);
  }

  private void enforceUserLimit(UUID tenantId) {
    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
    Integer maxUsers = tenant.getMaxUsers();
    if (maxUsers == null || maxUsers <= 0) {
      return;
    }
    long seats =
        memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            tenantId, List.of("ACTIVE", "SUSPENDED", "INVITED"));
    if (seats >= maxUsers) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "TENANT_USER_LIMIT",
          "Se alcanzó el límite de usuarios del tenant (" + maxUsers + ").");
    }
  }

  private AppUser resolveUser(String email, String displayName) {
    Optional<AppUser> existing = users.findByEmail(email);
    if (existing.isPresent()) {
      AppUser user = existing.get();
      if (!memberships.findByUserIdAndStatus(user.getId(), "ACTIVE").isEmpty()) {
        throw new AuthException(
            HttpStatus.CONFLICT, "USER_EXISTS", "Ya existe un usuario activo con ese correo.");
      }
      return user;
    }
    return users.save(AppUser.create(email, displayName));
  }

  private Membership resolveMembership(UUID userId, UUID tenantId) {
    Optional<Membership> existing =
        memberships.findFirstByUserIdAndTenantIdAndDeletedAtIsNull(userId, tenantId);
    if (existing.isPresent()) {
      Membership membership = existing.get();
      if ("ACTIVE".equals(membership.getStatus())) {
        throw new AuthException(
            HttpStatus.CONFLICT, "USER_EXISTS", "Este usuario ya pertenece al tenant.");
      }
      membership.setStatus("INVITED");
      return memberships.save(membership);
    }
    return memberships.save(Membership.invited(userId, tenantId));
  }

  private void revokePendingInvitations(UUID tenantId, String email) {
    invitations
        .findByTenantIdAndEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(tenantId, email)
        .forEach(
            pending -> {
              pending.revoke();
              invitations.save(pending);
            });
  }

  private void replaceRoles(UUID tenantId, UUID membershipId, List<String> roleCodes) {
    membershipRoles.findByMembershipId(membershipId).forEach(membershipRoles::delete);
    assignRoles(tenantId, membershipId, roleCodes);
  }

  private Membership requireMembership(UUID membershipId, UUID tenantId) {
    Membership membership =
        memberships
            .findById(membershipId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", "Membresía no encontrada."));
    if (!membership.getTenantId().equals(tenantId)) {
      throw new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "No autorizado para esta acción.");
    }
    if (membership.getDeletedAt() != null) {
      throw new AuthException(
          HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", "Membresía no encontrada.");
    }
    return membership;
  }

  private Membership requireActiveMembership(UUID membershipId, UUID tenantId) {
    Membership membership = requireMembership(membershipId, tenantId);
    if (!"ACTIVE".equals(membership.getStatus())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "MEMBERSHIP_NOT_ACTIVE", "La membresía no está activa.");
    }
    return membership;
  }

  private AdminDtos.InvitationItem toInvitationItem(UserInvitation invitation, Instant now) {
    String status;
    if (invitation.getRevokedAt() != null) {
      status = "REVOKED";
    } else if (invitation.getAcceptedAt() != null) {
      status = "ACCEPTED";
    } else if (invitation.getExpiresAt().isBefore(now)) {
      status = "EXPIRED";
    } else {
      status = "PENDING";
    }
    Role role = resolvePrimaryRole(invitation.getMembershipId());
    return new AdminDtos.InvitationItem(
        invitation.getId(),
        invitation.getEmail(),
        invitation.getDisplayName(),
        role != null ? role.getCode() : null,
        role != null ? role.getName() : "—",
        invitation.getCreatedAt(),
        invitation.getExpiresAt(),
        status);
  }

  private <T> AdminDtos.PageResponse<T> paginate(List<T> items, int page, int size) {
    int safeSize = Math.max(1, Math.min(size, 100));
    int safePage = Math.max(0, page);
    int from = safePage * safeSize;
    if (from >= items.size()) {
      return new AdminDtos.PageResponse<>(List.of(), items.size(), safePage, safeSize);
    }
    int to = Math.min(from + safeSize, items.size());
    return new AdminDtos.PageResponse<>(items.subList(from, to), items.size(), safePage, safeSize);
  }

  private AdminDtos.TenantMemberItem toMemberItem(Membership membership, Instant now) {
    AppUser user =
        users
            .findById(membership.getUserId())
            .orElseThrow(
                () ->
                    new AuthException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
    Role role = resolvePrimaryRole(membership.getId());
    boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    String userStatus = locked ? "LOCKED" : user.getStatus();
    boolean mfaEnabled =
        mfaFactors.findByUserIdAndFactorTypeAndEnabledTrue(user.getId(), "TOTP").isPresent();
    return new AdminDtos.TenantMemberItem(
        user.getId(),
        membership.getId(),
        user.getEmail(),
        user.getDisplayName(),
        role != null ? role.getCode() : null,
        role != null ? role.getName() : "—",
        membership.getStatus(),
        userStatus,
        mfaEnabled,
        membership.getCreatedAt());
  }

  private Role resolvePrimaryRole(UUID membershipId) {
    return membershipRoles.findByMembershipId(membershipId).stream()
        .map(MembershipRole::getRoleId)
        .flatMap(roleId -> roles.findById(roleId).stream())
        .findFirst()
        .orElse(null);
  }

  private void assignRoles(UUID tenantId, UUID membershipId, List<String> roleCodes) {
    List<String> codes = roleCodes == null || roleCodes.isEmpty() ? List.of("ANALISTA") : roleCodes;
    for (String code : codes) {
      Role role =
          roles
              .findByTenantIdAndCode(tenantId, code)
              .orElseThrow(
                  () ->
                      new AuthException(
                          HttpStatus.BAD_REQUEST, "ROLE_NOT_FOUND", "Rol no encontrado: " + code));
      membershipRoles.save(MembershipRole.assign(membershipId, role.getId(), tenantId));
    }
  }

  private void audit(String event, String objectType, UUID objectId, String result) {
    var principal = AuthContext.get();
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
