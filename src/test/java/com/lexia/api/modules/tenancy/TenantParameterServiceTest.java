package com.lexia.api.modules.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.identity.TenantParameter;
import com.lexia.api.modules.identity.TenantParameterRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantParameterServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @Mock private TenantParameterRepository parameters;

  @InjectMocks private TenantParameterService service;

  @Test
  void getInviteTtlHoursClampsOutOfRange() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS))
        .thenReturn(Optional.of(TenantParameter.of(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS, "999")));

    assertEquals(168, service.getInviteTtlHours(TENANT_ID));
  }

  @Test
  void getInviteTtlHoursDefaultsWhenMissing() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS))
        .thenReturn(Optional.empty());

    assertEquals(72, service.getInviteTtlHours(TENANT_ID));
  }

  @Test
  void isMfaRequiredParsesBoolean() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_MFA_REQUIRED))
        .thenReturn(Optional.of(TenantParameter.of(TENANT_ID, TenantParameterService.KEY_MFA_REQUIRED, "TRUE")));

    assertTrue(service.isMfaRequired(TENANT_ID));
  }

  @Test
  void setValueUpdatesExistingParameter() {
    TenantParameter existing =
        TenantParameter.of(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS, "72");
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS))
        .thenReturn(Optional.of(existing));

    service.setValue(TENANT_ID, TenantParameterService.KEY_INVITE_TTL_HOURS, "48");

    assertEquals("48", existing.getParamValue());
    verify(parameters).save(existing);
  }

  @Test
  void getCaseCodePatternDefaultsWhenMissing() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_CASE_CODE_PATTERN))
        .thenReturn(Optional.empty());

    assertEquals("LEX-YYYY-###", service.getCaseCodePattern(TENANT_ID));
  }

  @Test
  void getSlaDefaultHoursClampsOutOfRange() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_SLA_DEFAULT_HOURS))
        .thenReturn(Optional.of(TenantParameter.of(TENANT_ID, TenantParameterService.KEY_SLA_DEFAULT_HOURS, "99999")));

    assertEquals(8760, service.getSlaDefaultHours(TENANT_ID));
  }

  @Test
  void setValueCreatesWhenMissing() {
    when(parameters.findByTenantIdAndParamKey(TENANT_ID, TenantParameterService.KEY_MFA_REQUIRED))
        .thenReturn(Optional.empty());
    when(parameters.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.setValue(TENANT_ID, TenantParameterService.KEY_MFA_REQUIRED, "true");

    ArgumentCaptor<TenantParameter> captor = ArgumentCaptor.forClass(TenantParameter.class);
    verify(parameters).save(captor.capture());
    assertEquals("true", captor.getValue().getParamValue());
  }
}
