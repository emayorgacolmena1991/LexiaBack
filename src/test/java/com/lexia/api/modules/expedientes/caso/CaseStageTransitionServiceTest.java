package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
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
import com.lexia.api.modules.expedientes.ejd.EjdConnectorDispatchService;
import com.lexia.api.modules.expedientes.ejd.EjdGateEvaluationService;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageTransition;
import com.lexia.api.modules.expedientes.proceso.ProcessStageTransitionRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;

@ExtendWith(MockitoExtension.class)
class CaseStageTransitionServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");
  private static final UUID CASE_ID = UUID.randomUUID();
  private static final UUID STAGE_E1_ID = UUID.randomUUID();
  private static final UUID STAGE_E2_ID = UUID.randomUUID();
  private static final UUID ROW_E1_ID = UUID.randomUUID();
  private static final UUID ROW_E2_ID = UUID.randomUUID();

  @Mock private LegalCaseRepository legalCases;
  @Mock private CaseStageRepository caseStages;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private ProcessStageTransitionRepository stageTransitions;
  @Mock private CaseValidationRepository caseValidations;
  @Mock private CaseActionRepository caseActions;
  @Mock private CaseNoteRepository caseNotes;
  @Mock private AuthorizationService authorization;
  @Mock private TenantParameterService tenantParameters;
  @Mock private SlaCalendarService slaCalendar;
  @Mock private EjdGateEvaluationService gateEvaluation;
  @Mock private EjdConnectorDispatchService connectorDispatch;

  @InjectMocks private CaseStageTransitionService transitionService;

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
  void previewAllowsAdvanceWhenNextStageExists() {
    stubCaseAtE1();
    when(caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of());
    when(gateEvaluation.blockReasonForAdvance(CASE_ID, TENANT_ID, "e1")).thenReturn(null);

    var preview = transitionService.preview(CASE_ID, TENANT_ID);

    assertTrue(preview.canAdvance());
    assertEquals("e2", preview.nextStageCode());
  }

  @Test
  void previewBlocksWhenValidationFails() {
    stubCaseAtE1();
    when(caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(
            List.of(
                CaseValidation.create(
                    TENANT_ID, CASE_ID, UUID.randomUUID(), "V1", "Regla", "FAIL", "v1")));

    var preview = transitionService.preview(CASE_ID, TENANT_ID);

    assertFalse(preview.canAdvance());
  }

  @Test
  void advanceCompletesCurrentAndActivatesNext() {
    stubCaseAtE1();
    when(caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of());
    when(stageTransitions.findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
            TENANT_ID, PROCESS_ID, "e1", "e2"))
        .thenReturn(Optional.of(new ProcessStageTransition()));
    when(slaCalendar.resolveSlaHours(TENANT_ID, null)).thenReturn(72);
    when(slaCalendar.computeStageDueAt(org.mockito.ArgumentMatchers.eq(TENANT_ID), org.mockito.ArgumentMatchers.any(Instant.class), org.mockito.ArgumentMatchers.eq(72)))
        .thenReturn(Instant.parse("2026-09-22T12:00:00Z"));
    when(gateEvaluation.blockReasonForAdvance(CASE_ID, TENANT_ID, "e1")).thenReturn(null);

    transitionService.advance(CASE_ID, new ExpedienteDtos.StageAdvanceRequest(null, null));

    verify(caseStages, times(2)).save(any(CaseStage.class));
    verify(legalCases).save(any(LegalCase.class));
    verify(connectorDispatch).dispatchForStage(any(LegalCase.class), org.mockito.ArgumentMatchers.eq("e2"), org.mockito.ArgumentMatchers.eq("STAGE_ADVANCE"));
  }

  @Test
  void previewAtFirstStageCannotRevert() {
    stubCaseAtE1();
    when(caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of());

    var preview = transitionService.preview(CASE_ID, TENANT_ID);

    assertFalse(preview.canRevert());
  }

  @Test
  void previewAllowsRevertWhenIncomingTransitionExists() {
    stubCaseAtE2();
    when(stageTransitions.findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
            TENANT_ID, PROCESS_ID, "e1", "e2"))
        .thenReturn(Optional.of(new ProcessStageTransition()));

    var preview = transitionService.preview(CASE_ID, TENANT_ID);

    assertTrue(preview.canRevert());
    assertEquals("e1", preview.previousStageCode());
  }

  @Test
  void revertActivatesPreviousStage() {
    stubCaseAtE2();
    when(stageTransitions.findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
            TENANT_ID, PROCESS_ID, "e1", "e2"))
        .thenReturn(Optional.of(new ProcessStageTransition()));
    when(slaCalendar.resolveSlaHours(TENANT_ID, null)).thenReturn(72);
    when(slaCalendar.computeStageDueAt(org.mockito.ArgumentMatchers.eq(TENANT_ID), org.mockito.ArgumentMatchers.any(Instant.class), org.mockito.ArgumentMatchers.eq(72)))
        .thenReturn(Instant.parse("2026-09-22T12:00:00Z"));

    transitionService.revert(
        CASE_ID, new ExpedienteDtos.StageRevertRequest(null, "Corrección documental solicitada por cliente."));

    verify(caseStages, times(2)).save(any(CaseStage.class));
    verify(connectorDispatch).dispatchForStage(any(LegalCase.class), org.mockito.ArgumentMatchers.eq("e1"), org.mockito.ArgumentMatchers.eq("STAGE_REVERT"));
  }

  private void stubCaseAtE1() {
    LegalCase legalCase =
        LegalCase.create(
            TENANT_ID, "LEX-1", "EJD", "Escrituración", "Asunto", "MEDIA", null, UUID.randomUUID(), null);
    legalCase.attachProcess(PROCESS_ID, ROW_E1_ID);
    setField(legalCase, "id", CASE_ID);

    ProcessStageDef e1 = stageDef(STAGE_E1_ID, "e1", "Recepción", 1);
    ProcessStageDef e2 = stageDef(STAGE_E2_ID, "e2", "Estudio", 2);

    CaseStage rowE1 = CaseStage.create(TENANT_ID, CASE_ID, STAGE_E1_ID, "current", null);
    setField(rowE1, "id", ROW_E1_ID);
    CaseStage rowE2 = CaseStage.create(TENANT_ID, CASE_ID, STAGE_E2_ID, "pending", null);
    setField(rowE2, "id", ROW_E2_ID);

    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASE_ID, TENANT_ID))
        .thenReturn(Optional.of(legalCase));
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(e1, e2));
    when(caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(rowE1, rowE2));
  }

  private void stubCaseAtE2() {
    LegalCase legalCase =
        LegalCase.create(
            TENANT_ID, "LEX-1", "EJD", "Escrituración", "Asunto", "MEDIA", null, UUID.randomUUID(), null);
    legalCase.attachProcess(PROCESS_ID, ROW_E2_ID);
    setField(legalCase, "id", CASE_ID);

    ProcessStageDef e1 = stageDef(STAGE_E1_ID, "e1", "Recepción", 1);
    ProcessStageDef e2 = stageDef(STAGE_E2_ID, "e2", "Estudio", 2);

    CaseStage rowE1 = CaseStage.create(TENANT_ID, CASE_ID, STAGE_E1_ID, "completed", null);
    setField(rowE1, "id", ROW_E1_ID);
    CaseStage rowE2 = CaseStage.create(TENANT_ID, CASE_ID, STAGE_E2_ID, "current", null);
    setField(rowE2, "id", ROW_E2_ID);

    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASE_ID, TENANT_ID))
        .thenReturn(Optional.of(legalCase));
    when(stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(e1, e2));
    when(caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(rowE1, rowE2));
  }

  private static ProcessStageDef stageDef(UUID id, String code, String label, int order) {
    ProcessStageDef def = new ProcessStageDef();
    setField(def, "id", id);
    setField(def, "code", code);
    setField(def, "label", label);
    setField(def, "sortOrder", order);
    setField(def, "processDefinitionId", PROCESS_ID);
    return def;
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
