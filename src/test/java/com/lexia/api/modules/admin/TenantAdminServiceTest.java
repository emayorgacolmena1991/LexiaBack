package com.lexia.api.modules.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.notifications.InAppNotificationService;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantAdminServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID SESSION_ID = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("d1000000-0000-7000-8000-000000000001");
  private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

  @Mock private TenantRepository tenants;
  @Mock private MembershipRepository memberships;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;
  @Mock private TenantParameterService tenantParameters;
  @Mock private InAppNotificationService inAppNotifications;

  @InjectMocks private TenantAdminService service;

  @BeforeEach
  void setPrincipal() {
    AuthContext.set(new AuthPrincipal(ACTOR_ID, SESSION_ID, TENANT_ID, MEMBERSHIP_ID));
  }

  @AfterEach
  void clearPrincipal() {
    AuthContext.clear();
  }

  @Test
  void updateSecuritySettingsPersistsTtlAndMfa() {
    doNothing().when(authorization).requirePermission(eq("admin:tenant:escribir"));
    when(tenantParameters.getInviteTtlHours(TENANT_ID)).thenReturn(96);
    when(tenantParameters.isMfaRequired(TENANT_ID)).thenReturn(true);

    AdminDtos.TenantSecuritySettings result =
        service.updateSecuritySettings(new AdminDtos.UpdateTenantSecurityRequest(96, true));

    verify(tenantParameters)
        .setValue(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS, "96");
    verify(tenantParameters)
        .setValue(TENANT_ID, TenantParameterService.KEY_MFA_REQUIRED, "true");
    verify(auditEvents).save(org.mockito.ArgumentMatchers.any());
    assertEquals(96, result.inviteTtlHours());
    assertEquals(true, result.mfaRequired());
  }

  @Test
  void getSecuritySettingsReadsParameters() {
    when(authorization.hasPermission("admin:tenant:escribir")).thenReturn(false);
    when(authorization.hasPermission("admin:tenant:leer")).thenReturn(true);
    when(tenantParameters.getInviteTtlHours(TENANT_ID)).thenReturn(48);
    when(tenantParameters.isMfaRequired(TENANT_ID)).thenReturn(false);

    AdminDtos.TenantSecuritySettings result = service.getSecuritySettings();

    assertEquals(48, result.inviteTtlHours());
    assertEquals(false, result.mfaRequired());
    verify(tenantParameters).getInviteTtlHours(eq(TENANT_ID));
    verify(tenantParameters).isMfaRequired(eq(TENANT_ID));
  }
}
