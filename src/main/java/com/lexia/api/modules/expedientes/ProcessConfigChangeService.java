package com.lexia.api.modules.expedientes;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigChangeService {

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessConfigChangeSetService changeSets;

  public ProcessConfigChangeService(
      ProcessDefinitionRepository processDefinitions,
      ProcessConfigChangeSetService changeSets) {
    this.processDefinitions = processDefinitions;
    this.changeSets = changeSets;
  }

  @Transactional
  public void markDraftByCaseType(UUID tenantId, String caseType) {
    markDraftByCaseType(
        tenantId,
        caseType,
        ChangeSetDomain.PROCESS,
        "Parametrización del flujo (etapas, gates o transiciones)",
        null);
  }

  @Transactional
  public void markDraftByCaseType(
      UUID tenantId, String caseType, String domain, String summary, String entityRef) {
    if (tenantId == null || caseType == null || caseType.isBlank()) {
      return;
    }
    String normalized = caseType.trim().toUpperCase(Locale.ROOT);
    processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, normalized)
        .ifPresent(
            process -> {
              if (!process.isHasUnpublishedChanges()) {
                process.setHasUnpublishedChanges(true);
                processDefinitions.save(process);
              }
              changeSets.recordItem(
                  tenantId, process.getId(), normalized, domain, summary, entityRef);
            });
  }
}
