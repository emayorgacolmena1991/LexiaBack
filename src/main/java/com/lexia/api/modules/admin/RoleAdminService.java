package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.MembershipRoleRepository;
import com.lexia.api.modules.identity.Permission;
import com.lexia.api.modules.identity.PermissionRepository;
import com.lexia.api.modules.identity.Role;
import com.lexia.api.modules.identity.RolePermission;
import com.lexia.api.modules.identity.RolePermissionRepository;
import com.lexia.api.modules.identity.RoleRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class RoleAdminService {

  private final RoleRepository roles;
  private final PermissionRepository permissions;
  private final RolePermissionRepository rolePermissions;
  private final MembershipRoleRepository membershipRoles;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;

  public RoleAdminService(
      RoleRepository roles,
      PermissionRepository permissions,
      RolePermissionRepository rolePermissions,
      MembershipRoleRepository membershipRoles,
      AuthorizationService authorization,
      AuditEventRepository auditEvents) {
    this.roles = roles;
    this.permissions = permissions;
    this.rolePermissions = rolePermissions;
    this.membershipRoles = membershipRoles;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.PermissionOption> listPermissions() {
    requireRolesAccess();
    return permissions.findAllByOrderByModuleCodeAscCodeAsc().stream()
        .map(
            permission ->
                new AdminDtos.PermissionOption(
                    permission.getCode(), permission.getName(), permission.getModuleCode()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.RoleDetail> listRoles() {
    requireRolesAccess();
    UUID tenantId = AuthContext.require().tenantId();
    return roles.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId).stream()
        .map(role -> toDetail(role, tenantId))
        .sorted(Comparator.comparing(AdminDtos.RoleDetail::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @Transactional
  public AdminDtos.RoleDetail createRole(AdminDtos.CreateRoleRequest request) {
    requireRolesWrite();
    UUID tenantId = AuthContext.require().tenantId();
    String normalizedCode = request.code().trim().toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
    if (roles.findByTenantIdAndCode(tenantId, normalizedCode).isPresent()) {
      throw new AuthException(HttpStatus.CONFLICT, "ROLE_EXISTS", "Ya existe un rol con ese código.");
    }
    Role role = roles.save(Role.create(tenantId, request.code(), request.name(), request.description()));
    replacePermissions(tenantId, role.getId(), request.permissionCodes());
    audit("admin.role.created", "role", role.getId(), "OK");
    return toDetail(role, tenantId);
  }

  @Transactional
  public AdminDtos.RoleDetail updateRole(UUID roleId, AdminDtos.UpdateRoleRequest request) {
    requireRolesWrite();
    UUID tenantId = AuthContext.require().tenantId();
    Role role = requireRole(roleId, tenantId);
    role.updateDetails(request.name(), request.description());
    roles.save(role);
    replacePermissions(tenantId, role.getId(), request.permissionCodes());
    audit("admin.role.updated", "role", role.getId(), "OK");
    return toDetail(role, tenantId);
  }

  @Transactional
  public void deleteRole(UUID roleId) {
    requireRolesWrite();
    UUID tenantId = AuthContext.require().tenantId();
    Role role = requireRole(roleId, tenantId);
    if (role.isSystem()) {
      throw new AuthException(
          HttpStatus.CONFLICT, "ROLE_IS_SYSTEM", "No puedes eliminar un rol del sistema.");
    }
    if (membershipRoles.countByRoleId(roleId) > 0) {
      throw new AuthException(
          HttpStatus.CONFLICT, "ROLE_IN_USE", "El rol está asignado a uno o más usuarios.");
    }
    role.softDelete();
    roles.save(role);
    rolePermissions.deleteByRoleIdAndTenantId(roleId, tenantId);
    audit("admin.role.deleted", "role", roleId, "OK");
  }

  private AdminDtos.RoleDetail toDetail(Role role, UUID tenantId) {
    List<String> permissionCodes =
        rolePermissions.findByRoleIdAndTenantId(role.getId(), tenantId).stream()
            .map(RolePermission::getPermissionId)
            .flatMap(id -> permissions.findById(id).stream())
            .map(Permission::getCode)
            .sorted()
            .toList();
    return new AdminDtos.RoleDetail(
        role.getId(),
        role.getCode(),
        role.getName(),
        role.getDescription(),
        role.isSystem(),
        permissionCodes,
        membershipRoles.countByRoleId(role.getId()));
  }

  private Role requireRole(UUID roleId, UUID tenantId) {
    return roles
        .findByIdAndTenantIdAndDeletedAtIsNull(roleId, tenantId)
        .orElseThrow(
            () -> new AuthException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "Rol no encontrado."));
  }

  private void replacePermissions(UUID tenantId, UUID roleId, List<String> permissionCodes) {
    rolePermissions.deleteByRoleIdAndTenantId(roleId, tenantId);
    if (permissionCodes == null || permissionCodes.isEmpty()) {
      return;
    }
    for (String code : permissionCodes) {
      Permission permission =
          permissions
              .findByCode(code.trim())
              .orElseThrow(
                  () ->
                      new AuthException(
                          HttpStatus.BAD_REQUEST,
                          "PERMISSION_NOT_FOUND",
                          "Permiso no encontrado: " + code));
      rolePermissions.save(RolePermission.assign(roleId, permission.getId(), tenantId));
    }
  }

  private void requireRolesAccess() {
    if (authorization.hasPermission("admin:roles:escribir")
        || authorization.hasPermission("admin:usuarios:leer")
        || authorization.hasPermission("admin:usuarios:invitar")) {
      return;
    }
    authorization.requirePermission("admin:roles:escribir");
  }

  private void requireRolesWrite() {
    authorization.requirePermission("admin:roles:escribir");
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
