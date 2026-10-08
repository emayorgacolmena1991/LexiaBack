package com.lexia.api.modules.expedientes.proceso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.proceso.ProcessAdminDtos.CreateGateRequest;
import com.lexia.api.modules.expedientes.proceso.ProcessAdminDtos.CreateValidationRequest;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.ProcessConfig;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lexia.api.modules.expedientes.tenant.TenantConfigService;

@ExtendWith(MockitoExtension.class)
class ProcessAdminServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER_ID = UUID.fromString("a1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private GateDefRepository gateDefs;
  @Mock private ValidationDefRepository validationDefs;
  @Mock private TenantConfigService tenantConfigService;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;
  @Mock private ProcessConfigChangeService configChanges;

  @InjectMocks private ProcessAdminService processAdminService;

  @BeforeEach
  void auth() {
    AuthContext.set(new AuthPrincipal(USER_ID, USER_ID, TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void createGateAssignsNextCodeAndSortOrder() {
    ProcessDefinition process = stubProcess();
    GateDef existing = new GateDef();
    setField(existing, "code", "G-003");
    setField(existing, "sortOrder", 3);
    when(gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(existing));
    when(gateDefs.save(any(GateDef.class))).thenAnswer(inv -> inv.getArgument(0));
    ProcessConfig config = org.mockito.Mockito.mock(ProcessConfig.class);
    when(tenantConfigService.getProcessConfigForAdmin("EJD")).thenReturn(config);

    ProcessConfig result =
        processAdminService.createGate("ejd", new CreateGateRequest("  ¿Cumple requisitos?  "));

    ArgumentCaptor<GateDef> captor = ArgumentCaptor.forClass(GateDef.class);
    verify(gateDefs).save(captor.capture());
    GateDef saved = captor.getValue();
    assertEquals("G-004", saved.getCode());
    assertEquals(4, saved.getSortOrder());
    assertEquals("¿Cumple requisitos?", saved.getQuestion());
    assertEquals(config, result);
    verify(configChanges).markDraftByCaseType(TENANT_ID, "EJD");
    verify(processDefinitions).save(process);
  }

  @Test
  void createValidationAssignsNextCodeAndSortOrder() {
    ProcessDefinition process = stubProcess();
    ValidationDef existing = new ValidationDef();
    setField(existing, "code", "V6");
    setField(existing, "sortOrder", 6);
    when(validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(existing));
    when(validationDefs.save(any(ValidationDef.class))).thenAnswer(inv -> inv.getArgument(0));
    ProcessConfig config = org.mockito.Mockito.mock(ProcessConfig.class);
    when(tenantConfigService.getProcessConfigForAdmin("EJD")).thenReturn(config);

    ProcessConfig result =
        processAdminService.createValidation(
            "EJD", new CreateValidationRequest("  Validación nueva  "));

    ArgumentCaptor<ValidationDef> captor = ArgumentCaptor.forClass(ValidationDef.class);
    verify(validationDefs).save(captor.capture());
    ValidationDef saved = captor.getValue();
    assertEquals("V7", saved.getCode());
    assertEquals(7, saved.getSortOrder());
    assertEquals("Validación nueva", saved.getLabel());
    assertEquals(config, result);
    verify(configChanges).markDraftByCaseType(TENANT_ID, "EJD");
    verify(processDefinitions).save(process);
  }

  private ProcessDefinition stubProcess() {
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);
    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
    return process;
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
