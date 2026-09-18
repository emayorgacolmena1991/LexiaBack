package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.TokenHasher;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
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
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class UserAdminService {

  private static final Duration INVITE_TTL = Duration.ofHours(72);

  private final AppUserRepository users;
  private final MembershipRepository memberships;
  private final MembershipRoleRepository membershipRoles;
  private final RoleRepository roles;
  private final UserInvitationRepository invitations;
  private final TenantRepository tenants;
  private final EmailNotificationService emails;
  private final AuthorizationService authorization;

  public UserAdminService(
      AppUserRepository users,
      MembershipRepository memberships,
      MembershipRoleRepository membershipRoles,
      RoleRepository roles,
      UserInvitationRepository invitations,
      TenantRepository tenants,
      EmailNotificationService emails,
      AuthorizationService authorization) {
    this.users = users;
    this.memberships = memberships;
    this.membershipRoles = membershipRoles;
    this.roles = roles;
    this.invitations = invitations;
    this.tenants = tenants;
    this.emails = emails;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.RoleOption> listRoles() {
    authorization.requirePermission("admin:usuarios:invitar");
    UUID tenantId = AuthContext.require().tenantId();
    return roles.findByTenantId(tenantId).stream()
        .map(role -> new AdminDtos.RoleOption(role.getCode(), role.getName()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.InvitationItem> listInvitations() {
    authorization.requirePermission("admin:usuarios:invitar");
    UUID tenantId = AuthContext.require().tenantId();
    Instant now = Instant.now();
    return invitations.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId).stream()
        .map(
            invitation -> {
              String status;
              if (invitation.getAcceptedAt() != null) {
                status = "ACCEPTED";
              } else if (invitation.getExpiresAt().isBefore(now)) {
                status = "EXPIRED";
              } else {
                status = "PENDING";
              }
              return new AdminDtos.InvitationItem(
                  invitation.getId(),
                  invitation.getEmail(),
                  invitation.getDisplayName(),
                  invitation.getExpiresAt(),
                  status);
            })
        .toList();
  }

  @Transactional
  public AdminDtos.InviteUserResponse invite(AdminDtos.InviteUserRequest request) {
    authorization.requirePermission("admin:usuarios:invitar");
    var principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    if (tenantId == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "Selecciona un tenant.");
    }

    String email = request.email().toLowerCase().trim();
    String displayName = request.displayName().trim();

    AppUser user = resolveUser(email, displayName);
    Membership membership = resolveMembership(user.getId(), tenantId);
    replaceRoles(tenantId, membership.getId(), request.roles());
    revokePendingInvitations(tenantId, email);

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
                Instant.now().plus(INVITE_TTL)));

    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
    emails.sendUserInvitation(email, displayName, rawToken, tenant.getName());

    return new AdminDtos.InviteUserResponse(invitation.getId(), email, invitation.getExpiresAt());
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
}
