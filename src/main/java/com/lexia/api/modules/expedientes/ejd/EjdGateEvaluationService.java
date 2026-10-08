package com.lexia.api.modules.expedientes.ejd;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.caso.CaseGate;
import com.lexia.api.modules.expedientes.caso.CaseGateRepository;
import com.lexia.api.modules.expedientes.caso.CaseStageRepository;
import com.lexia.api.modules.expedientes.caso.CaseWorkflowTypes;
import com.lexia.api.modules.expedientes.proceso.GateDef;
import com.lexia.api.modules.expedientes.proceso.GateDefRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdGateEvaluationService {

  private static final Map<String, String> MANUAL_GATE_MIN_STAGE =
      Map.of(
          "G-002", "e2",
          "G-003", "e3",
          "G-004", "e5",
          "G-005", "e8",
          "CO-G-002", "c4");

  private final GateDefRepository gateDefs;
  private final CaseGateRepository caseGates;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final EjdOperationDocumentReqRepository operationDocumentReqs;
  private final LegalDocumentRepository documents;
  private final EjdStageGateReqRepository stageGateReqs;
  private final LegalCaseRepository legalCases;

  public EjdGateEvaluationService(
      GateDefRepository gateDefs,
      CaseGateRepository caseGates,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      EjdOperationDocumentReqRepository operationDocumentReqs,
      LegalDocumentRepository documents,
      EjdStageGateReqRepository stageGateReqs,
      LegalCaseRepository legalCases) {
    this.gateDefs = gateDefs;
    this.caseGates = caseGates;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.operationDocumentReqs = operationDocumentReqs;
    this.documents = documents;
    this.stageGateReqs = stageGateReqs;
    this.legalCases = legalCases;
  }

  @Transactional
  public void refreshForCase(LegalCase legalCase) {
    if (legalCase == null
        || !CaseWorkflowTypes.isOrchestrated(legalCase.getCaseType())
        || legalCase.getProcessDefinitionId() == null) {
      return;
    }
    UUID tenantId = legalCase.getTenantId();
    UUID caseId = legalCase.getId();
    UUID processId = legalCase.getProcessDefinitionId();

    Map<UUID, GateDef> gateDefById =
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .collect(Collectors.toMap(GateDef::getId, Function.identity()));
    Map<String, Integer> sortByCode =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .collect(Collectors.toMap(def -> def.getCode().toLowerCase(Locale.ROOT), ProcessStageDef::getSortOrder));

    String currentStageCode = resolveCurrentStageCode(caseId, tenantId, processId);
    int currentSort = sortByCode.getOrDefault(currentStageCode.toLowerCase(Locale.ROOT), 0);

    for (CaseGate caseGate : caseGates.findByCaseIdAndTenantIdOrderByGateDefIdAsc(caseId, tenantId)) {
      GateDef def = gateDefById.get(caseGate.getGateDefId());
      if (def == null) {
        continue;
      }
      String code = def.getCode();
      if ("G-001".equals(code)) {
        applyDocumentGate(caseGate, legalCase);
        continue;
      }
      if ("CO-G-001".equals(code)) {
        applyExecutiveTitleGate(caseGate, legalCase);
        continue;
      }
      String minStage = MANUAL_GATE_MIN_STAGE.get(code);
      if (minStage == null) {
        continue;
      }
      int minSort = sortByCode.getOrDefault(minStage, Integer.MAX_VALUE);
      if (currentSort < minSort) {
        continue;
      }
      if ("PASS".equals(caseGate.getResult()) || "FAIL".equals(caseGate.getResult())) {
        continue;
      }
      caseGate.applyResult("REVIEW_REQUIRED");
      caseGates.save(caseGate);
    }
  }

  @Transactional(readOnly = true)
  public String blockReasonForAdvance(UUID caseId, UUID tenantId, String fromStageCode) {
    if (fromStageCode == null || fromStageCode.isBlank()) {
      return null;
    }
    LegalCase legalCase =
        legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    if (legalCase == null || legalCase.getProcessDefinitionId() == null) {
      return null;
    }
    Map<String, GateDef> gateByCode =
        gateDefs
            .findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
                legalCase.getProcessDefinitionId(), tenantId)
            .stream()
            .collect(Collectors.toMap(GateDef::getCode, Function.identity()));
    Map<UUID, String> resultByDefId =
        caseGates.findByCaseIdAndTenantIdOrderByGateDefIdAsc(caseId, tenantId).stream()
            .collect(Collectors.toMap(CaseGate::getGateDefId, CaseGate::getResult));

    List<EjdStageGateReq> required =
        stageGateReqs.findByTenantIdAndStageCodeOrderBySortOrderAsc(
            tenantId, fromStageCode.trim().toLowerCase(Locale.ROOT));
    for (EjdStageGateReq req : required) {
      GateDef def = gateByCode.get(req.getGateCode());
      if (def == null) {
        continue;
      }
      String result = resultByDefId.get(def.getId());
      if (result == null || (!"PASS".equals(result) && !"YES".equals(result))) {
        return "El gate " + req.getGateCode() + " debe estar en «Cumple» antes de avanzar de etapa.";
      }
    }
    return null;
  }

  private void applyExecutiveTitleGate(CaseGate caseGate, LegalCase legalCase) {
    boolean ok =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                legalCase.getId(), legalCase.getTenantId())
            .stream()
            .map(LegalDocument::getDocType)
            .filter(type -> type != null && !type.isBlank())
            .map(type -> type.trim().toUpperCase(Locale.ROOT))
            .anyMatch(type -> type.contains("TITULO") || type.contains("EJECUTIVO"));
    caseGate.applyResult(ok ? "PASS" : "FAIL");
    caseGates.save(caseGate);
  }

  private void applyDocumentGate(CaseGate caseGate, LegalCase legalCase) {
    String operation = legalCase.getOperationTypeCode();
    if (operation == null || operation.isBlank()) {
      caseGate.applyResult("FAIL");
      caseGates.save(caseGate);
      return;
    }
    String op = operation.trim().toUpperCase(Locale.ROOT);
    List<String> required =
        operationDocumentReqs
            .findByTenantIdOrderByOperationCodeAscSortOrderAsc(legalCase.getTenantId())
            .stream()
            .filter(row -> op.equals(row.getOperationCode()))
            .map(EjdOperationDocumentReq::getDocumentTypeCode)
            .toList();
    if (required.isEmpty()) {
      caseGate.applyResult("REVIEW_REQUIRED");
      caseGates.save(caseGate);
      return;
    }
    Set<String> present = new HashSet<>();
    for (LegalDocument document :
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            legalCase.getId(), legalCase.getTenantId())) {
      if (document.getDocType() != null && !document.getDocType().isBlank()) {
        present.add(document.getDocType().trim().toUpperCase(Locale.ROOT));
      }
    }
    boolean ok =
        required.stream().allMatch(code -> present.contains(code.trim().toUpperCase(Locale.ROOT)));
    caseGate.applyResult(ok ? "PASS" : "FAIL");
    caseGates.save(caseGate);
  }

  private String resolveCurrentStageCode(UUID caseId, UUID tenantId, UUID processId) {
    Map<UUID, String> codeByDefId =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .collect(Collectors.toMap(ProcessStageDef::getId, ProcessStageDef::getCode));
    return caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(caseId, tenantId).stream()
        .filter(row -> "current".equals(row.getStatus()))
        .map(row -> codeByDefId.get(row.getStageDefId()))
        .filter(code -> code != null)
        .findFirst()
        .orElse("");
  }
}
