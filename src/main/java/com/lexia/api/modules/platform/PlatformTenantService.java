package com.lexia.api.modules.platform;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.SessionInvalidationService;
import com.lexia.api.modules.auth.UserCredentialRepository;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.LegalEntity;
import com.lexia.api.modules.identity.LegalEntityRepository;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.identity.MembershipRole;
import com.lexia.api.modules.identity.MembershipRoleRepository;
import com.lexia.api.modules.identity.Permission;
import com.lexia.api.modules.identity.PermissionRepository;
import com.lexia.api.modules.identity.Role;
import com.lexia.api.modules.identity.RolePermission;
import com.lexia.api.modules.identity.RolePermissionRepository;
import com.lexia.api.modules.identity.RoleRepository;
import com.lexia.api.modules.auth.TokenHasher;
import com.lexia.api.modules.identity.UserInvitation;
import com.lexia.api.modules.identity.UserInvitationRepository;
import com.lexia.api.modules.notary.NotaryTenantProfile;
import com.lexia.api.modules.notary.NotaryTenantProfileRepository;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantBinder;
import com.lexia.api.modules.tenancy.TenantModule;
import com.lexia.api.modules.tenancy.TenantModuleRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.tenancy.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class PlatformTenantService {

  private static final String PERM_READ = "platform:tenant:leer";
  private static final String PERM_PROVISION = "platform:tenant:provision";
  private static final String PERM_WRITE = "platform:tenant:escribir";

  private static final Set<String> ALLOWED_KINDS = Set.of("LEGAL_FIRM", "NOTARY");
  private static final List<String> DEFAULT_FIRM_MODULES =
      List.of("OPERACION_LEGAL", "NOTARIA", "COMPLIANCE");
  private static final List<String> DEFAULT_NOTARY_MODULES = List.of("NOTARIA", "OPERACION_LEGAL");

  private final TenantRepository tenants;
  private final TenantModuleRepository tenantModules;
  private final LegalEntityRepository legalEntities;
  private final RoleRepository roles;
  private final PermissionRepository permissions;
  private final RolePermissionRepository rolePermissions;
  private final AppUserRepository users;
  private final MembershipRepository memberships;
  private final MembershipRoleRepository membershipRoles;
  private final UserInvitationRepository invitations;
  private final NotaryTenantProfileRepository notaryProfiles;
  private final AuthorizationService authorization;
  private final TenantBinder tenantBinder;
  private final TenantParameterService tenantParameters;
  private final EmailNotificationService emails;
  private final AuditEventRepository auditEvents;
  private final UserCredentialRepository credentials;
  private final BrevoProperties brevoProperties;
  private final SessionInvalidationService sessionInvalidation;
  private final TransactionTemplate provisionFullTx;

  public PlatformTenantService(
      TenantRepository tenants,
      TenantModuleRepository tenantModules,
      LegalEntityRepository legalEntities,
      RoleRepository roles,
      PermissionRepository permissions,
      RolePermissionRepository rolePermissions,
      AppUserRepository users,
      MembershipRepository memberships,
      MembershipRoleRepository membershipRoles,
      UserInvitationRepository invitations,
      NotaryTenantProfileRepository notaryProfiles,
      AuthorizationService authorization,
      TenantBinder tenantBinder,
      TenantParameterService tenantParameters,
      EmailNotificationService emails,
      AuditEventRepository auditEvents,
      UserCredentialRepository credentials,
      BrevoProperties brevoProperties,
      SessionInvalidationService sessionInvalidation,
      PlatformTransactionManager transactionManager) {
    this.tenants = tenants;
    this.tenantModules = tenantModules;
    this.legalEntities = legalEntities;
    this.roles = roles;
    this.permissions = permissions;
    this.rolePermissions = rolePermissions;
    this.users = users;
    this.memberships = memberships;
    this.membershipRoles = membershipRoles;
    this.invitations = invitations;
    this.notaryProfiles = notaryProfiles;
    this.authorization = authorization;
    this.tenantBinder = tenantBinder;
    this.tenantParameters = tenantParameters;
    this.emails = emails;
    this.auditEvents = auditEvents;
    this.credentials = credentials;
    this.brevoProperties = brevoProperties;
    this.sessionInvalidation = sessionInvalidation;
    this.provisionFullTx = new TransactionTemplate(transactionManager);
    this.provisionFullTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public List<PlatformDtos.TenantSummary> listTenants() {
    requirePlatformOperator();
    authorization.requirePermission(PERM_READ);
    return tenants.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
        .filter(t -> !"PLATFORM".equals(t.getTenantKind()))
        .map(this::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public PlatformDtos.TenantDetail getTenant(UUID tenantId) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_READ);
    return toDetail(requireClientTenant(tenantId));
  }

  @Transactional
  public PlatformDtos.TenantDetail updateTenant(
      UUID tenantId, PlatformDtos.UpdateTenantRequest request) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_WRITE);
    Tenant tenant = requireClientTenant(tenantId);
    assertTenantMutable(tenant);
    tenant.updatePlatformGovernance(
        request.name(),
        request.timezone(),
        request.planCode(),
        request.maxUsers(),
        request.maxCases());
    tenants.save(tenant);
    tenantParameters.setValue(tenant.getId(), "timezone", request.timezone().trim());
    audit("platform.tenant.updated", "tenant", tenant.getId(), "OK");
    return toDetail(tenant);
  }

  @Transactional
  public PlatformDtos.TenantDetail updateTenantStatus(UUID tenantId, String statusRaw) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_WRITE);
    Tenant tenant = requireClientTenant(tenantId);
    String status = statusRaw.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE", "SUSPENDED", "CLOSED").contains(status)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Estado no válido para el tenant.");
    }
    String current = tenant.getStatus();
    if ("CLOSED".equals(current)) {
      throw new AuthException(
          HttpStatus.CONFLICT, "TENANT_CLOSED", "El tenant está cerrado y no admite cambios.");
    }
    if (status.equals(current)) {
      return toDetail(tenant);
    }
    validateStatusTransition(current, status);
    tenant.setOperationalStatus(status);
    tenants.save(tenant);
    if ("SUSPENDED".equals(status) || "CLOSED".equals(status)) {
      sessionInvalidation.invalidateTenantSessions(tenant.getId());
    }
    audit(statusAuditEvent(status), "tenant", tenant.getId(), "OK");
    return toDetail(tenant);
  }

  @Transactional
  public PlatformDtos.InviteAdminResponse inviteAdmin(
      UUID tenantId, PlatformDtos.InviteAdminRequest request) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_WRITE);
    PrimaryAdminOutcome outcome = issueAdditionalAdmin(tenantId, request.email(), request.displayName());
    return toInviteAdminResponse(outcome);
  }

  @Transactional
  public PlatformDtos.E2eInviteAdminResponse inviteAdminForE2e(
      UUID tenantId, PlatformDtos.InviteAdminRequest request) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_WRITE);
    PrimaryAdminOutcome outcome = issueAdditionalAdmin(tenantId, request.email(), request.displayName());
    PlatformDtos.InviteAdminResponse base = toInviteAdminResponse(outcome);
    String token = outcome.rawInviteToken();
    String acceptUrl =
        token == null
            ? null
            : brevoProperties.getFrontendUrl() + "/invitacion?token=" + token;
    return new PlatformDtos.E2eInviteAdminResponse(
        base.adminActivated(),
        base.invitationId(),
        base.invitationExpiresAt(),
        token,
        acceptUrl);
  }

  private PrimaryAdminOutcome issueAdditionalAdmin(
      UUID tenantId, String emailRaw, String displayNameRaw) {
    Tenant tenant = requireClientTenant(tenantId);
    assertTenantMutable(tenant);
    enforceUserLimit(tenant);
    UUID legalEntityId = resolvePrimaryLegalEntityId(tenantId);
    tenantBinder.bind(tenantId);
    return issuePrimaryAdmin(tenant, legalEntityId, emailRaw, displayNameRaw, AdminInviteKind.ADDITIONAL);
  }

  private static PlatformDtos.InviteAdminResponse toInviteAdminResponse(PrimaryAdminOutcome outcome) {
    return new PlatformDtos.InviteAdminResponse(
        outcome.activatedImmediately(), outcome.invitationId(), outcome.invitationExpiresAt());
  }

  private void enforceUserLimit(Tenant tenant) {
    Integer maxUsers = tenant.getMaxUsers();
    if (maxUsers == null || maxUsers <= 0) {
      return;
    }
    long seats =
        memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            tenant.getId(), List.of("ACTIVE", "SUSPENDED", "INVITED"));
    if (seats >= maxUsers) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "TENANT_USER_LIMIT",
          "Se alcanzó el límite de usuarios del tenant (" + maxUsers + ").");
    }
  }

  private UUID resolvePrimaryLegalEntityId(UUID tenantId) {
    return legalEntities.findByTenantId(tenantId).stream()
        .filter(e -> e.getDeletedAt() == null)
        .map(LegalEntity::getId)
        .findFirst()
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.CONFLICT,
                    "LEGAL_ENTITY_MISSING",
                    "El tenant no tiene persona jurídica configurada."));
  }

  private static void validateStatusTransition(String current, String target) {
    if ("ACTIVE".equals(target) && !"SUSPENDED".equals(current)) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "INVALID_STATUS_TRANSITION",
          "Solo puedes reactivar un tenant suspendido.");
    }
    if ("SUSPENDED".equals(target) && !"ACTIVE".equals(current)) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "INVALID_STATUS_TRANSITION",
          "Solo puedes suspender un tenant activo.");
    }
    if ("CLOSED".equals(target) && !"ACTIVE".equals(current) && !"SUSPENDED".equals(current)) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "INVALID_STATUS_TRANSITION",
          "Solo puedes cerrar un tenant activo o suspendido.");
    }
  }

  private static String statusAuditEvent(String status) {
    return switch (status) {
      case "ACTIVE" -> "platform.tenant.reactivated";
      case "SUSPENDED" -> "platform.tenant.suspended";
      case "CLOSED" -> "platform.tenant.closed";
      default -> "platform.tenant.status_updated";
    };
  }

  private static void assertTenantMutable(Tenant tenant) {
    if ("CLOSED".equals(tenant.getStatus())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "TENANT_CLOSED", "El tenant está cerrado de forma definitiva.");
    }
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public PlatformDtos.ProvisionTenantResponse provision(PlatformDtos.ProvisionTenantRequest request) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_PROVISION);
    UUID operatorTenant = AuthContext.get() != null ? AuthContext.get().tenantId() : null;
    ProvisionResult result = executeProvision(request);
    restoreOperatorTenant(operatorTenant);
    audit("platform.tenant.provisioned", "tenant", result.tenant().getId(), "OK");
    return toProvisionResponse(result);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public PlatformDtos.E2eProvisionTenantResponse provisionForE2e(
      PlatformDtos.ProvisionTenantRequest request) {
    requirePlatformOperator();
    authorization.requirePermission(PERM_PROVISION);
    UUID operatorTenant = AuthContext.get() != null ? AuthContext.get().tenantId() : null;
    ProvisionResult result = executeProvision(request);
    restoreOperatorTenant(operatorTenant);
    audit("platform.tenant.provisioned", "tenant", result.tenant().getId(), "OK");
    PlatformDtos.ProvisionTenantResponse base = toProvisionResponse(result);
    String token = result.primaryAdmin().rawInviteToken();
    String acceptUrl =
        token == null
            ? null
            : brevoProperties.getFrontendUrl() + "/invitacion?token=" + token;
    return new PlatformDtos.E2eProvisionTenantResponse(
        base.tenantId(),
        base.code(),
        base.name(),
        base.tenantKind(),
        base.primaryAdminActivated(),
        base.primaryAdminInvitationId(),
        base.invitationExpiresAt(),
        token,
        acceptUrl);
  }

  private ProvisionResult executeProvision(PlatformDtos.ProvisionTenantRequest request) {
    String code = request.code().trim().toLowerCase(Locale.ROOT);
    if (tenants.findByCode(code).isPresent()) {
      throw new AuthException(HttpStatus.CONFLICT, "TENANT_CODE_EXISTS", "Ya existe un tenant con ese código.");
    }

    String kind = request.tenantKind().trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_KINDS.contains(kind)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "INVALID_TENANT_KIND", "Solo se admite LEGAL_FIRM o NOTARY.");
    }
    if ("NOTARY".equals(kind) && request.notaryProfile() == null) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "NOTARY_PROFILE_REQUIRED", "Indica el perfil notarial (RUC, cantón, nombre).");
    }

    String timezone =
        request.timezone() != null && !request.timezone().isBlank()
            ? request.timezone().trim()
            : "America/Guayaquil";

    return provisionFullTx.execute(
        status -> {
          Tenant savedTenant = persistControlTenantShell(request, code, kind, timezone);
          tenantBinder.bind(savedTenant.getId());
          return completeProvisionAppLayer(savedTenant, request, kind, timezone);
        });
  }

  private Tenant persistControlTenantShell(
      PlatformDtos.ProvisionTenantRequest request, String code, String kind, String timezone) {
    Tenant tenant =
        tenants.save(
            Tenant.provision(
                code,
                request.name(),
                kind,
                timezone,
                "SHARED",
                request.planCode(),
                request.maxUsers(),
                request.maxCases()));
    List<String> modules = resolveModules(kind, request.modules());
    for (String moduleCode : modules) {
      tenantModules.save(TenantModule.enable(tenant.getId(), moduleCode));
    }
    return tenant;
  }

  private ProvisionResult completeProvisionAppLayer(
      Tenant tenant, PlatformDtos.ProvisionTenantRequest request, String kind, String timezone) {
    tenantBinder.bind(tenant.getId());
    seedDefaultParameters(tenant.getId(), timezone);

    PlatformDtos.LegalEntityInput le = request.legalEntity();
    String entityType =
        "NOTARY".equals(kind)
            ? "NOTARY"
            : (le.entityType() != null ? le.entityType() : "COMPANY");
    LegalEntity legalEntity =
        legalEntities.save(
            LegalEntity.create(
                tenant.getId(),
                le.nit(),
                le.legalName(),
                le.tradeName(),
                entityType,
                le.domicile()));

    roles.save(
        Role.createSystem(
            tenant.getId(), "ADMIN", "Administrador", "Administrador principal del tenant"));

    if ("NOTARY".equals(kind)) {
      PlatformDtos.NotaryProfileInput np = request.notaryProfile();
      if (notaryProfiles.findByRuc(np.ruc()).isPresent()) {
        throw new AuthException(
            HttpStatus.CONFLICT, "NOTARY_RUC_EXISTS", "Ya hay una notaría registrada con ese RUC.");
      }
      notaryProfiles.save(
          NotaryTenantProfile.create(
              tenant.getId(),
              np.ruc(),
              np.canton(),
              np.notaryNumber(),
              np.officialName(),
              np.notaryHolderName(),
              np.contactEmail(),
              np.listed() == null || np.listed()));
    }

    PlatformDtos.PrimaryAdminInput admin = request.primaryAdmin();
    tenantBinder.bind(tenant.getId());
    PrimaryAdminOutcome primaryAdmin =
        issuePrimaryAdmin(
            tenant,
            legalEntity.getId(),
            admin.email(),
            admin.displayName(),
            AdminInviteKind.PROVISION_PRIMARY);

    UUID adminRoleId = findAdminRoleId(tenant.getId());
    grantAdminPermissions(adminRoleId, tenant.getId());
    return new ProvisionResult(tenant, primaryAdmin);
  }

  private void seedDefaultParameters(UUID tenantId, String timezone) {
    tenantParameters.setValue(tenantId, "timezone", timezone);
    tenantParameters.setValue(
        tenantId, TenantParameterService.KEY_CASE_CODE_PATTERN, "LEX-YYYY-###");
    tenantParameters.setValue(tenantId, TenantParameterService.KEY_SLA_DEFAULT_HOURS, "72");
    tenantParameters.setValue(tenantId, TenantParameterService.KEY_INVITE_TTL_HOURS, "72");
    tenantParameters.setValue(tenantId, TenantParameterService.KEY_MFA_REQUIRED, "false");
  }

  private static PlatformDtos.ProvisionTenantResponse toProvisionResponse(ProvisionResult result) {
    Tenant tenant = result.tenant();
    PrimaryAdminOutcome admin = result.primaryAdmin();
    return new PlatformDtos.ProvisionTenantResponse(
        tenant.getId(),
        tenant.getCode(),
        tenant.getName(),
        tenant.getTenantKind(),
        admin.activatedImmediately(),
        admin.invitationId(),
        admin.invitationExpiresAt());
  }

  private record ProvisionResult(Tenant tenant, PrimaryAdminOutcome primaryAdmin) {}

  private record PrimaryAdminOutcome(
      boolean activatedImmediately,
      UUID invitationId,
      Instant invitationExpiresAt,
      String rawInviteToken) {}

  private enum AdminInviteKind {
    PROVISION_PRIMARY,
    ADDITIONAL
  }

  private List<String> resolveModules(String tenantKind, List<String> requested) {
    if (requested != null && !requested.isEmpty()) {
      return requested.stream().map(m -> m.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }
    return "NOTARY".equals(tenantKind) ? DEFAULT_NOTARY_MODULES : DEFAULT_FIRM_MODULES;
  }

  private void grantAdminPermissions(UUID roleId, UUID tenantId) {
    List<Permission> all = permissions.findAllByOrderByModuleCodeAscCodeAsc();
    List<RolePermission> rows = new ArrayList<>();
    for (Permission permission : all) {
      if (permission.getCode().startsWith("platform:")) {
        continue;
      }
      rows.add(RolePermission.assign(roleId, permission.getId(), tenantId));
    }
    rolePermissions.saveAll(rows);
  }

  private PrimaryAdminOutcome issuePrimaryAdmin(
      Tenant tenant,
      UUID legalEntityId,
      String emailRaw,
      String displayNameRaw,
      AdminInviteKind kind) {
    var principal = AuthContext.require();
    String email = emailRaw.toLowerCase(Locale.ROOT).trim();
    String displayName = displayNameRaw.trim();

    AppUser user =
        users
            .findByEmail(email)
            .orElseGet(() -> users.save(AppUser.create(email, displayName)));

    boolean hasCredential = credentials.findByUserId(user.getId()).isPresent();

    Optional<Membership> existing =
        memberships.findFirstByUserIdAndTenantIdAndDeletedAtIsNull(user.getId(), tenant.getId());
    Membership membership;
    if (existing.isPresent()) {
      membership = existing.get();
      if ("ACTIVE".equals(membership.getStatus())) {
        throw new AuthException(
            HttpStatus.CONFLICT, "USER_EXISTS", "Este usuario ya pertenece al tenant.");
      }
      membership.setStatus(hasCredential ? "ACTIVE" : "INVITED");
      membership = memberships.save(membership);
    } else {
      membership =
          memberships.save(
              hasCredential
                  ? Membership.active(user.getId(), tenant.getId(), legalEntityId)
                  : Membership.invited(user.getId(), tenant.getId(), legalEntityId));
    }

    membershipRoles.findByMembershipId(membership.getId()).forEach(membershipRoles::delete);
    tenantBinder.bind(tenant.getId());
    membershipRoles.save(
        MembershipRole.assign(membership.getId(), findAdminRoleId(tenant.getId()), tenant.getId()));

    if (hasCredential) {
      audit(
          kind == AdminInviteKind.PROVISION_PRIMARY
              ? "platform.tenant.primary_admin_granted"
              : "platform.tenant.admin_granted",
          "membership",
          membership.getId(),
          "OK");
      return new PrimaryAdminOutcome(true, null, null, null);
    }

    invitations
        .findByTenantIdAndEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(tenant.getId(), email)
        .forEach(
            pending -> {
              pending.revoke();
              invitations.save(pending);
            });

    Duration inviteTtl = Duration.ofHours(tenantParameters.getInviteTtlHours(tenant.getId()));
    String rawToken = TokenHasher.randomToken();
    UserInvitation invitation =
        invitations.save(
            UserInvitation.issue(
                tenant.getId(),
                email,
                displayName,
                TokenHasher.sha256(rawToken),
                principal.userId(),
                membership.getId(),
                Instant.now().plus(inviteTtl)));

    emails.sendUserInvitation(tenant.getId(), email, displayName, rawToken, tenant.getName());
    audit(
        kind == AdminInviteKind.PROVISION_PRIMARY
            ? "platform.tenant.primary_admin_invited"
            : "platform.tenant.admin_invited",
        "user_invitation",
        invitation.getId(),
        "OK");
    return new PrimaryAdminOutcome(
        false, invitation.getId(), invitation.getExpiresAt(), rawToken);
  }

  private UUID findAdminRoleId(UUID tenantId) {
    return roles.findByTenantIdAndCode(tenantId, "ADMIN")
        .orElseThrow(
            () -> new AuthException(HttpStatus.CONFLICT, "ROLE_NOT_FOUND", "Rol ADMIN no encontrado."))
        .getId();
  }

  private Tenant requireClientTenant(UUID tenantId) {
    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
    if (tenant.getDeletedAt() != null) {
      throw new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado.");
    }
    if ("PLATFORM".equals(tenant.getTenantKind())) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "PLATFORM_TENANT_IMMUTABLE", "No se gestiona el tenant de plataforma aquí.");
    }
    return tenant;
  }

  private PlatformDtos.TenantDetail toDetail(Tenant tenant) {
    return new PlatformDtos.TenantDetail(
        tenant.getId(),
        tenant.getCode(),
        tenant.getName(),
        tenant.getTenantKind(),
        tenant.getStatus(),
        tenant.getTimezone(),
        tenant.getPlanCode(),
        tenant.getMaxUsers(),
        tenant.getMaxCases(),
        tenant.getCreatedAt(),
        tenant.getUpdatedAt());
  }

  private void requirePlatformOperator() {
    UUID tenantId = AuthContext.require().tenantId();
    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
    if (!"PLATFORM".equals(tenant.getTenantKind())) {
      throw new AuthException(
          HttpStatus.FORBIDDEN,
          "PLATFORM_TENANT_REQUIRED",
          "Cambia a la organización LEXIA Plataforma para operar tenants.");
    }
  }

  private PlatformDtos.TenantSummary toSummary(Tenant tenant) {
    return new PlatformDtos.TenantSummary(
        tenant.getId(),
        tenant.getCode(),
        tenant.getName(),
        tenant.getTenantKind(),
        tenant.getStatus(),
        tenant.getPlanCode(),
        tenant.getCreatedAt());
  }

  private void audit(String event, String objectType, UUID objectId, String result) {
    var principal = AuthContext.get();
    UUID actor = principal != null ? principal.userId() : null;
    HttpServletRequest request = currentRequest();
    String ip = request != null ? clientIp(request) : null;
    String userAgent = request != null ? request.getHeader("User-Agent") : null;
    // tenant_id NULL: evento de plataforma (RLS audit_event permite NULL).
    provisionFullTx.executeWithoutResult(
        tx -> auditEvents.save(AuditEvent.of(null, actor, event, objectType, objectId, result, ip, userAgent)));
  }

  private void restoreOperatorTenant(UUID operatorTenantId) {
    if (operatorTenantId != null) {
      tenantBinder.bind(operatorTenantId);
    }
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
