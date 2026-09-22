package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleValidationRelinkServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");

  @Mock private ValidationDefRepository validationDefs;
  @Mock private ProcessDefinitionRepository processDefinitions;

  @InjectMocks private RuleValidationRelinkService relinkService;

  @Test
  void parseRuleBindingAcceptsEjdCodes() {
    var binding = RuleValidationRelinkService.parseRuleBinding("EJD.V3");
    assertTrue(binding.isPresent());
    assertEquals("EJD", binding.get().vertical());
    assertEquals("V3", binding.get().validationCode());
  }

  @Test
  void relinkUpdatesValidationsPointingToRetiredRule() {
    UUID oldRuleId = UUID.randomUUID();
    UUID newRuleId = UUID.randomUUID();
    RuleDef activated = rule(newRuleId, "EJD.V1", 2);

    ValidationDef validation = new ValidationDef();
    setField(validation, "id", UUID.randomUUID());
    setField(validation, "code", "V1");
    validation.setRuleDefId(oldRuleId);

    when(validationDefs.findByTenantIdAndRuleDefId(TENANT_ID, oldRuleId))
        .thenReturn(List.of(validation));

    int count = relinkService.relinkOnActivation(TENANT_ID, activated, oldRuleId);

    assertEquals(1, count);
    assertEquals(newRuleId, validation.getRuleDefId());
    verify(validationDefs).save(validation);
  }

  @Test
  void relinkByCodeWhenNoPreviousPointer() {
    UUID newRuleId = UUID.randomUUID();
    RuleDef activated = rule(newRuleId, "EJD.V2", 2);
    ProcessDefinition process = new ProcessDefinition();
    setField(process, "id", PROCESS_ID);

    ValidationDef validation = new ValidationDef();
    setField(validation, "code", "V2");

    when(processDefinitions.findByTenantIdAndCaseTypeAndDeletedAtIsNull(TENANT_ID, "EJD"))
        .thenReturn(Optional.of(process));
    when(validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(validation));

    int count = relinkService.relinkOnActivation(TENANT_ID, activated, null);

    assertEquals(1, count);
    assertEquals(newRuleId, validation.getRuleDefId());
  }

  private static RuleDef rule(UUID id, String code, int version) {
    RuleDef rule = new RuleDef();
    setField(rule, "id", id);
    setField(rule, "code", code);
    setField(rule, "version", version);
    return rule;
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
