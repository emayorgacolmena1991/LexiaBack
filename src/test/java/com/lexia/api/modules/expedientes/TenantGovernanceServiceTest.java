package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantGovernanceServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @Mock private TenantAiPolicyRepository aiPolicies;
  @Mock private TenantFeatureFlagRepository featureFlags;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;

  @InjectMocks private TenantGovernanceService service;

  @BeforeEach
  void auth() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void featureFlagDisabledWhenExpired() {
    TenantFeatureFlag flag = new TenantFeatureFlag();
    setField(flag, "enabled", true);
    setField(flag, "expiresAt", Instant.parse("2020-01-01T00:00:00Z"));
    setField(flag, "code", TenantFeatureFlagCodes.WORKSPACE_AI_ASSIST);
    when(featureFlags.findByTenantIdAndCode(TENANT_ID, TenantFeatureFlagCodes.WORKSPACE_AI_ASSIST))
        .thenReturn(Optional.of(flag));

    assertFalse(service.isFeatureEnabled(TENANT_ID, TenantFeatureFlagCodes.WORKSPACE_AI_ASSIST));
  }

  @Test
  void featureFlagEnabledWhenActive() {
    TenantFeatureFlag flag = new TenantFeatureFlag();
    setField(flag, "enabled", true);
    setField(flag, "expiresAt", null);
    setField(flag, "code", TenantFeatureFlagCodes.DOCUMENT_AI_EXTRACTION);
    when(featureFlags.findByTenantIdAndCode(TENANT_ID, TenantFeatureFlagCodes.DOCUMENT_AI_EXTRACTION))
        .thenReturn(Optional.of(flag));

    assertTrue(service.isFeatureEnabled(TENANT_ID, TenantFeatureFlagCodes.DOCUMENT_AI_EXTRACTION));
  }

  private static void setField(Object target, String field, Object value) {
    try {
      var f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
