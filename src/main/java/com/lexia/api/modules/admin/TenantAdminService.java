package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import com.lexia.api.modules.tenancy.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
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
public class TenantAdminService {

  private final TenantRepository tenants;
  private final MembershipRepository memberships;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final TenantParameterService tenantParameters;
  private final InAppNotificationService inAppNotifications;

  public TenantAdminService(
      TenantRepository tenants,
      MembershipRepository memberships,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      TenantParameterService tenantParameters,
      InAppNotificationService inAppNotifications) {
    this.tenants = tenants;
    this.memberships = memberships;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.tenantParameters = tenantParameters;
    this.inAppNotifications = inAppNotifications;
  }

  @Transactional(readOnly = true)
  public AdminDtos.TenantDetail getCurrentTenant() {
    requireTenantRead();
    UUID tenantId = AuthContext.require().tenantId();
    Tenant tenant = requireTenant(tenantId);
    long activeMembers =
        memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            tenantId, List.of("ACTIVE", "SUSPENDED", "INVITED"));
    return toDetail(tenant, activeMembers);
  }

  @Transactional
  public AdminDtos.TenantDetail updateCurrentTenant(AdminDtos.UpdateTenantRequest request) {
    requireTenantWrite();
    UUID tenantId = AuthContext.require().tenantId();
    Tenant tenant = requireTenant(tenantId);
    if (request.maxUsers() != null && request.maxUsers() > 0) {
      long seats =
          memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
              tenantId, List.of("ACTIVE", "SUSPENDED", "INVITED"));
      if (request.maxUsers() < seats) {
        throw new AuthException(
            HttpStatus.CONFLICT,
            "TENANT_USER_LIMIT",
            "El límite no puede ser menor que los usuarios actuales (" + seats + ").");
      }
    }
    tenant.updateSettings(request.name(), request.timezone(), request.maxUsers());
    tenants.save(tenant);
    if (request.caseCodePattern() != null && !request.caseCodePattern().isBlank()) {
      tenantParameters.setValue(
          tenantId,
          TenantParameterService.KEY_CASE_CODE_PATTERN,
          request.caseCodePattern().trim());
    }
    if (request.slaDefaultHours() != null) {
      int hours = Math.max(1, Math.min(request.slaDefaultHours(), 8760));
      tenantParameters.setValue(
          tenantId, TenantParameterService.KEY_SLA_DEFAULT_HOURS, String.valueOf(hours));
    }
    audit("admin.tenant.updated", "tenant", tenant.getId(), "OK");
    inAppNotifications.onTenantUpdated(
        tenantId, AuthContext.require().userId(), tenant.getName());
    long activeMembers =
        memberships.countByTenantIdAndDeletedAtIsNullAndStatusIn(
            tenantId, List.of("ACTIVE", "SUSPENDED", "INVITED"));
    return toDetail(tenant, activeMembers);
  }

  @Transactional(readOnly = true)
  public AdminDtos.TenantSecuritySettings getSecuritySettings() {
    requireTenantRead();
    UUID tenantId = AuthContext.require().tenantId();
    return new AdminDtos.TenantSecuritySettings(
        tenantParameters.getInviteTtlHours(tenantId),
        tenantParameters.isMfaRequired(tenantId));
  }

  @Transactional
  public AdminDtos.TenantSecuritySettings updateSecuritySettings(
      AdminDtos.UpdateTenantSecurityRequest request) {
    requireTenantWrite();
    UUID tenantId = AuthContext.require().tenantId();
    if (request.inviteTtlHours() != null) {
      int hours = Math.max(24, Math.min(request.inviteTtlHours(), 168));
      tenantParameters.setValue(
          tenantId, TenantParameterService.KEY_INVITE_TTL_HOURS, String.valueOf(hours));
    }
    if (request.mfaRequired() != null) {
      tenantParameters.setValue(
          tenantId,
          TenantParameterService.KEY_MFA_REQUIRED,
          Boolean.TRUE.equals(request.mfaRequired()) ? "true" : "false");
    }
    audit("admin.tenant.security_updated", "tenant", tenantId, "OK");
    inAppNotifications.onSecurityUpdated(tenantId, AuthContext.require().userId());
    return new AdminDtos.TenantSecuritySettings(
        tenantParameters.getInviteTtlHours(tenantId),
        tenantParameters.isMfaRequired(tenantId));
  }

  private AdminDtos.TenantDetail toDetail(Tenant tenant, long activeMembers) {
    return new AdminDtos.TenantDetail(
        tenant.getId(),
        tenant.getCode(),
        tenant.getName(),
        tenant.getStatus(),
        tenant.getTimezone(),
        tenant.getPlanCode(),
        tenant.getMaxUsers(),
        tenant.getMaxCases(),
        activeMembers,
        tenantParameters.getCaseCodePattern(tenant.getId()),
        tenantParameters.getSlaDefaultHours(tenant.getId()),
        tenant.getUpdatedAt());
  }

  private Tenant requireTenant(UUID tenantId) {
    return tenants
        .findById(tenantId)
        .orElseThrow(
            () -> new AuthException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
  }

  private void requireTenantRead() {
    if (authorization.hasPermission("admin:tenant:escribir")
        || authorization.hasPermission("admin:tenant:leer")
        || authorization.hasPermission("admin:usuarios:invitar")) {
      return;
    }
    authorization.requirePermission("admin:tenant:leer");
  }

  private void requireTenantWrite() {
    authorization.requirePermission("admin:tenant:escribir");
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
