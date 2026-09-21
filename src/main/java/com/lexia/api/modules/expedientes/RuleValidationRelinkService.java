package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class RuleValidationRelinkService {

  private final ValidationDefRepository validationDefs;
  private final ProcessDefinitionRepository processDefinitions;

  public RuleValidationRelinkService(
      ValidationDefRepository validationDefs, ProcessDefinitionRepository processDefinitions) {
    this.validationDefs = validationDefs;
    this.processDefinitions = processDefinitions;
  }

  @Transactional
  public int relinkOnActivation(UUID tenantId, RuleDef activatedRule, UUID previousActiveRuleId) {
    int updated = 0;
    if (previousActiveRuleId != null) {
      List<ValidationDef> linked =
          validationDefs.findByTenantIdAndRuleDefId(tenantId, previousActiveRuleId);
      for (ValidationDef def : linked) {
        def.setRuleDefId(activatedRule.getId());
        validationDefs.save(def);
        updated++;
      }
    }
    if (updated > 0) {
      return updated;
    }
    return relinkByRuleCode(tenantId, activatedRule);
  }

  private int relinkByRuleCode(UUID tenantId, RuleDef activatedRule) {
    Optional<RuleBinding> binding = parseRuleBinding(activatedRule.getCode());
    if (binding.isEmpty()) {
      return 0;
    }
    RuleBinding parsed = binding.get();
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, parsed.vertical())
        .map(
            process -> {
              List<ValidationDef> defs =
                  validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
                      process.getId(), tenantId);
              int count = 0;
              for (ValidationDef def : defs) {
                if (parsed.validationCode().equals(def.getCode())) {
                  def.setRuleDefId(activatedRule.getId());
                  validationDefs.save(def);
                  count++;
                }
              }
              return count;
            })
        .orElse(0);
  }

  static Optional<RuleBinding> parseRuleBinding(String ruleCode) {
    if (ruleCode == null || ruleCode.isBlank()) {
      return Optional.empty();
    }
    String normalized = ruleCode.trim().toUpperCase(Locale.ROOT);
    int dot = normalized.indexOf('.');
    if (dot <= 0 || dot >= normalized.length() - 1) {
      return Optional.empty();
    }
    String vertical = normalized.substring(0, dot);
    if (!"EJD".equals(vertical) && !"ECD".equals(vertical)) {
      return Optional.empty();
    }
    String validationCode = normalized.substring(dot + 1);
    return Optional.of(new RuleBinding(vertical, validationCode));
  }

  record RuleBinding(String vertical, String validationCode) {}
}
