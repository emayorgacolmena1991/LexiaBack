package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.admin.Integration;
import com.lexia.api.modules.admin.IntegrationCall;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EjdConnectorDispatchServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");
  private static final UUID CASE_ID = UUID.randomUUID();
  private static final UUID STAGE_E1_ID = UUID.randomUUID();
  private static final UUID INTEGRATION_ID = UUID.randomUUID();

  @Mock private IntegrationRepository integrations;
  @Mock private IntegrationCallRepository integrationCalls;
  @Mock private EjdStageIntegrationRepository stageIntegrations;
  @Mock private OutboxEventRepository outboxEvents;
  @Mock private LegalCaseRepository legalCases;
  @Mock private CaseStageRepository caseStages;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private AuthorizationService authorization;
  @Spy private EjdIntegrationProperties properties = new EjdIntegrationProperties();
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private EjdConnectorDispatchService dispatchService;

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
  void dispatchForStageSkipsNonOrchestratedCases() {
    LegalCase litigio =
        LegalCase.create(
            TENANT_ID, "LIT-1", "LITIGIO", "Litigio", "Asunto", "MEDIA", null, UUID.randomUUID(), null);

    dispatchService.dispatchForStage(litigio, "e1", "STAGE_ADVANCE");

    verify(stageIntegrations, never()).findByTenantIdAndStageCodeOrderBySortOrderAsc(any(), any());
  }

  @Test
  void dispatchForStagePersistsCallAndOutboxWhenStubLive() {
    LegalCase legalCase = ejdCase();
    when(stageIntegrations.findByTenantIdAndStageCodeOrderBySortOrderAsc(TENANT_ID, "e1"))
        .thenReturn(List.of(EjdStageIntegration.create(TENANT_ID, "e1", "NOTARIA", 1)));
    when(integrations.findByTenantIdAndCode(TENANT_ID, "NOTARIA"))
        .thenReturn(Optional.of(integration("NOTARIA", true)));
    when(integrationCalls.findByTenantIdAndIntegrationIdAndIdempotencyKey(
            TENANT_ID, INTEGRATION_ID, idempotencyKey("STAGE_ADVANCE")))
        .thenReturn(Optional.empty());

    dispatchService.dispatchForStage(legalCase, "e1", "STAGE_ADVANCE");

    ArgumentCaptor<IntegrationCall> captor = ArgumentCaptor.forClass(IntegrationCall.class);
    verify(integrationCalls, org.mockito.Mockito.times(2)).save(captor.capture());
    assertEquals("SUCCEEDED", captor.getAllValues().get(1).getStatus());
    verify(outboxEvents, org.mockito.Mockito.times(2)).save(any(OutboxEvent.class));
  }

  @Test
  void dispatchForStageLeavesPendingWhenWorkerMode() {
    properties.setStubLive(false);
    LegalCase legalCase = ejdCase();
    when(stageIntegrations.findByTenantIdAndStageCodeOrderBySortOrderAsc(TENANT_ID, "e1"))
        .thenReturn(List.of(EjdStageIntegration.create(TENANT_ID, "e1", "NOTARIA", 1)));
    when(integrations.findByTenantIdAndCode(TENANT_ID, "NOTARIA"))
        .thenReturn(Optional.of(integration("NOTARIA", true)));
    when(integrationCalls.findByTenantIdAndIntegrationIdAndIdempotencyKey(
            TENANT_ID, INTEGRATION_ID, idempotencyKey("STAGE_ADVANCE")))
        .thenReturn(Optional.empty());

    dispatchService.dispatchForStage(legalCase, "e1", "STAGE_ADVANCE");

    ArgumentCaptor<IntegrationCall> captor = ArgumentCaptor.forClass(IntegrationCall.class);
    verify(integrationCalls).save(captor.capture());
    assertEquals("PENDING", captor.getValue().getStatus());
    verify(outboxEvents).save(any(OutboxEvent.class));
  }

  @Test
  void invokeManuallyReturnsSucceededWhenStubLive() {
    LegalCase legalCase = ejdCase();
    stubCurrentStageE1(legalCase);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASE_ID, TENANT_ID))
        .thenReturn(Optional.of(legalCase));
    when(integrations.findByTenantIdAndCode(TENANT_ID, "NOTARIA"))
        .thenReturn(Optional.of(integration("NOTARIA", true)));

    var result = dispatchService.invokeManually(CASE_ID, "notaria");

    verify(authorization).requirePermission("expedientes:caso:escribir");
    assertEquals("NOTARIA", result.connectorCode());
    assertEquals("SUCCEEDED", result.callStatus());
  }

  private LegalCase ejdCase() {
    LegalCase legalCase =
        LegalCase.create(
            TENANT_ID, "LEX-1", "EJD", "Escrituración", "Asunto", "MEDIA", null, UUID.randomUUID(), null);
    legalCase.attachProcess(PROCESS_ID, STAGE_E1_ID);
    setField(legalCase, "id", CASE_ID);
    return legalCase;
  }

  private void stubCurrentStageE1(LegalCase legalCase) {
    ProcessStageDef e1 = new ProcessStageDef();
    setField(e1, "id", STAGE_E1_ID);
    setField(e1, "code", "e1");
    CaseStage row = CaseStage.create(TENANT_ID, CASE_ID, STAGE_E1_ID, "current", null);
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(e1));
    when(caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(legalCase.getId(), TENANT_ID))
        .thenReturn(List.of(row));
  }

  private Integration integration(String code, boolean enabled) {
    Integration row = new Integration();
    setField(row, "id", INTEGRATION_ID);
    setField(row, "tenantId", TENANT_ID);
    setField(row, "code", code);
    setField(row, "name", code);
    setField(row, "enabled", enabled);
    return row;
  }

  private String idempotencyKey(String trigger) {
    return "case:"
        + CASE_ID
        + ":stage:"
        + "e1"
        + ":connector:"
        + "NOTARIA"
        + ":trigger:"
        + trigger;
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
