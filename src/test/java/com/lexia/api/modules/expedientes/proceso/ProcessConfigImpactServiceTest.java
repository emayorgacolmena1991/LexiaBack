package com.lexia.api.modules.expedientes.proceso;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigDiffView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigImpactView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;

@ExtendWith(MockitoExtension.class)
class ProcessConfigImpactServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessConfigPublicationRepository publications;
  @Mock private ProcessConfigSnapshotService snapshotService;
  @Mock private ProcessConfigDiffService diffService;
  @Mock private RuleDefRepository rules;
  @Mock private LegalCaseRepository cases;

  private ProcessConfigImpactService impactService;

  @BeforeEach
  void setup() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
    impactService =
        new ProcessConfigImpactService(
            processDefinitions,
            publications,
            snapshotService,
            diffService,
            rules,
            cases,
            new ObjectMapper());
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void analyzeImportedSnapshotBlocksUnknownTransitionStage() {
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);
    setField(process, "tenantId", TENANT_ID);
    setField(process, "caseType", "EJD");
    process.setHasUnpublishedChanges(true);
    setField(process, "configVersion", 1);

    String snapshot =
        """
        {"stages":[{"code":"e1","label":"E1"}],
         "transitions":[{"fromStageCode":"e1","toStageCode":"e9"}],
         "validations":[]}
        """;

    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
    when(publications.findByTenantIdAndProcessDefinitionIdAndConfigVersion(
            TENANT_ID, PROCESS_ID, 1))
        .thenReturn(Optional.empty());
    when(diffService.diff(anyInt(), anyInt(), any(), anyString()))
        .thenReturn(new ProcessConfigDiffView(1, 2, true, null, List.of()));
    when(cases.countByTenantIdAndCaseTypeAndDeletedAtIsNullAndProcessConfigVersionIsNot(
            TENANT_ID, "EJD", 1))
        .thenReturn(0L);

    ProcessConfigImpactView view = impactService.analyzeImportedSnapshot("EJD", snapshot);

    assertFalse(view.ready());
    assertTrue(
        view.findings().stream().anyMatch(f -> f.code().startsWith("TRANSITION_TO_UNKNOWN_STAGE")));
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
