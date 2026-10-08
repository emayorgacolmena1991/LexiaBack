package com.lexia.api.modules.expedientes.proceso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ImpactFinding;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigDiffView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigImpactView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.reglas.RuleDef;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigImpactService {

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessConfigPublicationRepository publications;
  private final ProcessConfigSnapshotService snapshotService;
  private final ProcessConfigDiffService diffService;
  private final RuleDefRepository rules;
  private final LegalCaseRepository cases;
  private final ObjectMapper objectMapper;

  public ProcessConfigImpactService(
      ProcessDefinitionRepository processDefinitions,
      ProcessConfigPublicationRepository publications,
      @Lazy ProcessConfigSnapshotService snapshotService,
      ProcessConfigDiffService diffService,
      RuleDefRepository rules,
      LegalCaseRepository cases,
      ObjectMapper objectMapper) {
    this.processDefinitions = processDefinitions;
    this.publications = publications;
    this.snapshotService = snapshotService;
    this.diffService = diffService;
    this.rules = rules;
    this.cases = cases;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public ProcessConfigImpactView analyzeDraft(String caseTypeParam) {
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    String draftJson = snapshotService.captureSnapshotJson(process.getCaseType());
    return analyze(tenantId, process, draftJson);
  }

  @Transactional(readOnly = true)
  public ProcessConfigImpactView analyzeImportedSnapshot(String caseTypeParam, String snapshotJson) {
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    return analyze(tenantId, process, snapshotJson);
  }

  @Transactional(readOnly = true)
  public void requireReadyForPromotion(String caseTypeParam) {
    ProcessConfigImpactView impact = analyzeDraft(caseTypeParam);
    if (impact.ready()) {
      return;
    }
    String detail =
        impact.findings().stream()
            .filter(f -> "BLOCKER".equals(f.severity()))
            .map(ImpactFinding::message)
            .findFirst()
            .orElse("Hay bloqueos de dependencia antes de promover la configuración.");
    throw new AuthException(HttpStatus.CONFLICT, "CONFIG_IMPACT_BLOCKED", detail);
  }

  private ProcessConfigImpactView analyze(
      UUID tenantId, ProcessDefinition process, String proposedJson) {
    int activeVersion = process.getConfigVersion();
    int nextVersion = activeVersion + (process.isHasUnpublishedChanges() ? 1 : 0);
    String activeJson = activePublicationJson(tenantId, process.getId(), activeVersion);
    ProcessConfigDiffView diff =
        diffService.diff(activeVersion, nextVersion, activeJson, proposedJson);
    int changeCount = diff.changes() != null ? diff.changes().size() : 0;

    List<ImpactFinding> findings = new ArrayList<>();
    if (changeCount == 0 && process.isHasUnpublishedChanges()) {
      findings.add(info("NO_SNAPSHOT_DELTA", "El borrador no muestra diferencias en el snapshot."));
    }
    findings.addAll(validateSnapshotJson(tenantId, proposedJson));
    long priorCases =
        cases.countByTenantIdAndCaseTypeAndDeletedAtIsNullAndProcessConfigVersionIsNot(
            tenantId, process.getCaseType(), activeVersion);
    if (priorCases > 0) {
      findings.add(
          info(
              "CASES_ON_PRIOR_CONFIG",
              priorCases
                  + " expediente(s) abierto(s) permanecerán en versiones de configuración anteriores a v"
                  + activeVersion
                  + "; los nuevos usarán v"
                  + nextVersion
                  + " tras publicar."));
    }
    if (process.isHasUnpublishedChanges() && changeCount == 0) {
      findings.add(
          warning(
              "EMPTY_SNAPSHOT_DIFF",
              "Hay cambios pendientes pero el snapshot es idéntico a la versión activa; revise calendario/reglas fuera del snapshot."));
    }
    boolean ready = findings.stream().noneMatch(f -> "BLOCKER".equals(f.severity()));
    return new ProcessConfigImpactView(
        ready, changeCount, priorCases, activeVersion, nextVersion, findings);
  }

  private List<ImpactFinding> validateSnapshotJson(UUID tenantId, String json) {
    List<ImpactFinding> findings = new ArrayList<>();
    if (!StringUtils.hasText(json)) {
      findings.add(blocker("SNAPSHOT_EMPTY", "El snapshot de configuración está vacío."));
      return findings;
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      Set<String> stageCodes = new HashSet<>();
      JsonNode stages = root.get("stages");
      if (stages == null || !stages.isArray() || stages.isEmpty()) {
        findings.add(blocker("STAGES_REQUIRED", "Debe existir al menos una etapa en el proceso."));
      } else {
        for (JsonNode stage : stages) {
          String code = text(stage, "code");
          if (StringUtils.hasText(code)) {
            stageCodes.add(code);
          }
        }
      }
      JsonNode transitions = root.path("transitions");
      if (transitions.isArray()) {
        for (JsonNode edge : transitions) {
          checkStageRef(findings, stageCodes, text(edge, "fromStageCode"), "TRANSITION_FROM");
          checkStageRef(findings, stageCodes, text(edge, "toStageCode"), "TRANSITION_TO");
        }
      }
      JsonNode stageGates = root.path("stageGateRequirements");
      if (stageGates.isArray()) {
        for (JsonNode link : stageGates) {
          checkStageRef(findings, stageCodes, text(link, "stageCode"), "STAGE_GATE");
        }
      }
      JsonNode validations = root.path("validations");
      if (validations.isArray()) {
        for (JsonNode validation : validations) {
          String ruleCode = text(validation, "ruleCode");
          if (!StringUtils.hasText(ruleCode)) {
            continue;
          }
          Optional<RuleDef> active =
              rules.findFirstByTenantIdAndCodeAndStatusOrderByVersionDesc(
                  tenantId, ruleCode, "ACTIVE");
          if (active.isEmpty()) {
            findings.add(
                blocker(
                    "RULE_NOT_ACTIVE",
                    "La validación referencia la regla "
                        + ruleCode
                        + " sin versión ACTIVE publicada."));
          }
        }
      }
    } catch (Exception ex) {
      findings.add(blocker("SNAPSHOT_INVALID", "Snapshot inválido: " + ex.getMessage()));
    }
    return findings;
  }

  private static void checkStageRef(
      List<ImpactFinding> findings, Set<String> stageCodes, String code, String prefix) {
    if (!StringUtils.hasText(code)) {
      return;
    }
    if (!stageCodes.contains(code)) {
      findings.add(
          blocker(
              prefix + "_UNKNOWN_STAGE",
              "Referencia a etapa inexistente en el snapshot: " + code + "."));
    }
  }

  private String activePublicationJson(UUID tenantId, UUID processId, int activeVersion) {
    return publications
        .findByTenantIdAndProcessDefinitionIdAndConfigVersion(tenantId, processId, activeVersion)
        .map(ProcessConfigPublication::getSnapshotJson)
        .orElse(null);
  }

  private ProcessDefinition requireProcess(UUID tenantId, String caseTypeParam) {
    String caseType = caseTypeParam.trim().toUpperCase(Locale.ROOT);
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND,
                    "PROCESS_NOT_FOUND",
                    "No hay definición de proceso para " + caseType + "."));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && !value.isNull() ? value.asText() : null;
  }

  private static ImpactFinding blocker(String code, String message) {
    return new ImpactFinding("BLOCKER", code, message);
  }

  private static ImpactFinding warning(String code, String message) {
    return new ImpactFinding("WARNING", code, message);
  }

  private static ImpactFinding info(String code, String message) {
    return new ImpactFinding("INFO", code, message);
  }
}
