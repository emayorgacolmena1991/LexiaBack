package com.lexia.api.modules.expedientes.proceso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessPublicationState;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.PublishProcessConfigRequest;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lexia.api.modules.expedientes.tenant.TenantConfigService;

@ExtendWith(MockitoExtension.class)
class ProcessConfigPublishServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");
  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private ProcessConfigPublicationRepository publications;
  @Mock private TenantConfigService tenantConfigService;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;
  @Mock private ProcessConfigChangeSetService changeSetService;
  @Mock private ProcessConfigSnapshotService snapshotService;
  @Mock private ProcessConfigDiffService diffService;
  @Mock private ProcessConfigImpactService impactService;

  @InjectMocks private ProcessConfigPublishService publishService;

  @BeforeEach
  void auth() {
    AuthContext.set(new AuthPrincipal(USER_ID, UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void publishIncrementsVersionWhenDraftPending() {
    ProcessDefinition process = processWithDraft();
    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
    ProcessStageDef e1 = new ProcessStageDef();
    setField(e1, "code", "e1");
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(e1));
    ProcessConfigChangeSet approved = new ProcessConfigChangeSet();
    setField(approved, "id", UUID.randomUUID());
    when(changeSetService.requireApproved(TENANT_ID, PROCESS_ID)).thenReturn(approved);
    when(snapshotService.captureSnapshotJson("EJD")).thenReturn("{\"caseType\":\"EJD\"}");
    when(publications.save(any(ProcessConfigPublication.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tenantConfigService.getProcessConfigForAdmin("EJD")).thenReturn(mockConfig());

    publishService.publish("EJD", new PublishProcessConfigRequest("Publicación de prueba operativa."));

    verify(publications).save(any(ProcessConfigPublication.class));
    assertEquals(2, process.getConfigVersion());
    assertEquals(false, process.isHasUnpublishedChanges());
  }

  @Test
  void publishRejectsWhenNoPendingChanges() {
    ProcessDefinition process = processWithDraft();
    process.setHasUnpublishedChanges(false);
    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));

    assertThrows(
        AuthException.class,
        () ->
            publishService.publish(
                "EJD", new PublishProcessConfigRequest("Intento sin cambios pendientes.")));
  }

  private ProcessDefinition processWithDraft() {
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);
    setField(process, "tenantId", TENANT_ID);
    setField(process, "caseType", "EJD");
    setField(process, "code", "EJD");
    setField(process, "name", "Escrituración");
    process.setHasUnpublishedChanges(true);
    setField(process, "configVersion", 1);
    return process;
  }

  private ProcessConfig mockConfig() {
    return new ProcessConfig(
        PROCESS_ID,
        "EJD",
        "Escrituración",
        "EJD",
        List.of(),
        List.of(),
        List.of(),
        new ProcessPublicationState(1, true, null, null, null, null, null, null));
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
