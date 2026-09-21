package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.admin.Integration;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EjdIntegrationServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private EjdStageIntegrationRepository stageIntegrations;
  @Mock private IntegrationRepository integrations;
  @Mock private IntegrationCallRepository integrationCalls;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;

  @InjectMocks private EjdIntegrationService ejdIntegrationService;

  @Test
  void connectorsForStageReturnsLinkedIntegration() {
    Integration notaria = integration("NOTARIA", "Notaría", true);
    when(stageIntegrations.findByTenantIdAndStageCodeOrderBySortOrderAsc(TENANT_ID, "e5"))
        .thenReturn(List.of(EjdStageIntegration.create(TENANT_ID, "e5", "NOTARIA", 1)));
    when(integrations.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of(notaria));
    when(integrationCalls.findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
            notaria.getId(), TENANT_ID))
        .thenReturn(java.util.Optional.empty());

    var result = ejdIntegrationService.connectorsForStage(TENANT_ID, "e5");

    assertEquals(1, result.size());
    assertEquals("NOTARIA", result.get(0).code());
    assertEquals(true, result.get(0).enabled());
  }

  private static Integration integration(String code, String name, boolean enabled) {
    Integration row = new Integration();
    setField(row, "id", UUID.randomUUID());
    setField(row, "tenantId", TENANT_ID);
    setField(row, "code", code);
    setField(row, "name", name);
    setField(row, "enabled", enabled);
    return row;
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
