package com.lexia.api.modules.expedientes.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.CatalogRepository;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationService;
import com.lexia.api.modules.expedientes.ejd.EjdOperationDocumentReqRepository;
import com.lexia.api.modules.expedientes.proceso.GateDefRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigChangeSetService;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinition;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinitionRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;
import com.lexia.api.modules.expedientes.proceso.ValidationDefRepository;

@ExtendWith(MockitoExtension.class)
class TenantConfigServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private GateDefRepository gateDefs;
  @Mock private ValidationDefRepository validationDefs;
  @Mock private CatalogRepository catalogs;
  @Mock private CatalogItemRepository catalogItems;
  @Mock private TenantParameterService tenantParameters;
  @Mock private AuthorizationService authorization;
  @Mock private EjdOperationDocumentReqRepository operationDocumentReqs;
  @Mock private RuleDefRepository ruleDefs;
  @Mock private EjdIntegrationService ejdIntegrations;
  @Mock private ProcessConfigChangeSetService changeSetService;

  @InjectMocks private TenantConfigService tenantConfigService;

  @BeforeEach
  void setAuth() {
    AuthContext.set(
        new AuthPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void getConfigLoadsEjdProcess() {
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);
    setField(process, "code", "EJD");
    setField(process, "name", "Escrituración E1–E8");
    setField(process, "caseType", "EJD");

    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of());
    when(gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of());
    when(validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            PROCESS_ID, TENANT_ID))
        .thenReturn(List.of());
    when(catalogs.findByTenantIdAndCodeAndDeletedAtIsNull(any(), any())).thenReturn(Optional.empty());
    when(operationDocumentReqs.findByTenantIdOrderByOperationCodeAscSortOrderAsc(TENANT_ID))
        .thenReturn(List.of());
    when(ejdIntegrations.listEscrituracionConnectors(TENANT_ID)).thenReturn(List.of());
    when(tenantParameters.getCaseCodePattern(TENANT_ID)).thenReturn("LEX-YYYY-###");
    when(tenantParameters.getSlaDefaultHours(TENANT_ID)).thenReturn(72);

    var config = tenantConfigService.getConfig("EJD");

    assertEquals("EJD", config.caseType());
    assertEquals("Escrituración", config.vertical());
    assertEquals("EJD", config.process().code());
    assertEquals(72, config.slaDefaultHours());
  }

  @Test
  void getConfigRejectsInvalidCaseType() {
    assertThrows(AuthException.class, () -> tenantConfigService.getConfig("INVALID"));
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
