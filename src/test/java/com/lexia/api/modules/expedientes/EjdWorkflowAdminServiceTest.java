package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageGatesRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageTransitionsRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageTransitionPair;
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

@ExtendWith(MockitoExtension.class)
class EjdWorkflowAdminServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private GateDefRepository gateDefs;
  @Mock private ProcessStageTransitionRepository stageTransitions;
  @Mock private EjdStageGateReqRepository stageGateReqs;
  @Mock private AuthorizationService authorization;
  @Mock private ProcessConfigChangeService configChanges;

  @InjectMocks private EjdWorkflowAdminService workflowAdminService;

  @BeforeEach
  void auth() {
    AuthContext.set(
        new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void replaceStageTransitionsPersistsValidPairs() {
    stubEjdProcess();
    stubStages("e1", "e2");
    ProcessStageTransition saved = ProcessStageTransition.create(TENANT_ID, PROCESS_ID, "e1", "e2");
    when(stageTransitions.findByTenantIdAndProcessDefinitionIdOrderByFromStageCodeAsc(
            TENANT_ID, PROCESS_ID))
        .thenReturn(List.of(saved));

    var view =
        workflowAdminService.replaceStageTransitions(
            new ReplaceStageTransitionsRequest(
                List.of(new StageTransitionPair("e1", "e2"), new StageTransitionPair("e1", "e2"))));

    verify(stageTransitions).deleteByTenantIdAndProcessDefinitionId(TENANT_ID, PROCESS_ID);
    verify(stageTransitions).save(any(ProcessStageTransition.class));
    assertEquals(1, view.transitions().size());
    assertEquals("e1", view.transitions().get(0).fromStageCode());
    assertEquals("e2", view.transitions().get(0).toStageCode());
  }

  @Test
  void replaceStageGatesDeletesBeforeInsert() {
    stubEjdProcess();
    stubStages("e1");
    GateDef g1 = new GateDef();
    setField(g1, "code", "G-001");
    when(gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(g1));
    when(stageGateReqs.findByTenantIdOrderByStageCodeAscSortOrderAsc(TENANT_ID)).thenReturn(List.of());

    workflowAdminService.replaceStageGates("e1", new ReplaceStageGatesRequest(List.of("G-001")));

    var order = inOrder(stageGateReqs);
    order.verify(stageGateReqs).deleteByTenantIdAndStageCode(TENANT_ID, "e1");
    order.verify(stageGateReqs).flush();
    order.verify(stageGateReqs).save(any(EjdStageGateReq.class));
  }

  @Test
  void replaceStageTransitionsRejectsUnknownStage() {
    stubEjdProcess();
    stubStages("e1", "e2");

    assertThrows(
        AuthException.class,
        () ->
            workflowAdminService.replaceStageTransitions(
                new ReplaceStageTransitionsRequest(List.of(new StageTransitionPair("e1", "e9")))));
  }

  private void stubEjdProcess() {
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);
    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
  }

  private void stubStages(String... codes) {
    List<ProcessStageDef> defs =
        java.util.Arrays.stream(codes)
            .map(
                code -> {
                  ProcessStageDef def = new ProcessStageDef();
                  setField(def, "code", code);
                  setField(def, "label", code.toUpperCase());
                  setField(def, "processDefinitionId", PROCESS_ID);
                  return def;
                })
            .toList();
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(defs);
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
