package com.lexia.api.modules.expedientes.ejd;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.caso.CaseValidation;
import com.lexia.api.modules.expedientes.caso.CaseValidationRepository;
import com.lexia.api.modules.expedientes.caso.CaseWorkflowTypes;
import com.lexia.api.modules.expedientes.documentos.DocumentRequirement;
import com.lexia.api.modules.expedientes.documentos.DocumentRequirementRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.ecd.EcdDocumentReq;
import com.lexia.api.modules.expedientes.ecd.EcdDocumentReqRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.reglas.LexiaRulesEngine;
import com.lexia.api.modules.expedientes.reglas.RuleDef;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;
import com.lexia.api.modules.expedientes.proceso.ValidationDef;
import com.lexia.api.modules.expedientes.proceso.ValidationDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdValidationEvaluationService {

  private static final String RULE_V1 = "EJD.V1";
  private static final String RULE_V2 = "EJD.V2";
  private static final String RULE_V3 = "EJD.V3";
  private static final String RULE_V4 = "EJD.V4";
  private static final String RULE_V5 = "EJD.V5";
  private static final String RULE_V6 = "EJD.V6";

  private final ValidationDefRepository validationDefs;
  private final RuleDefRepository ruleDefs;
  private final CaseValidationRepository caseValidations;
  private final EjdOperationDocumentReqRepository operationDocumentReqs;
  private final DocumentRequirementRepository documentRequirements;
  private final EcdDocumentReqRepository ecdDocumentReqs;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository documentVersions;
  private final ExtractedDataRepository extractedData;
  private final EjdRuleBodyParser ruleBodyParser;
  private final LexiaRulesEngine rulesEngine;

  public EjdValidationEvaluationService(
      ValidationDefRepository validationDefs,
      RuleDefRepository ruleDefs,
      CaseValidationRepository caseValidations,
      EjdOperationDocumentReqRepository operationDocumentReqs,
      DocumentRequirementRepository documentRequirements,
      EcdDocumentReqRepository ecdDocumentReqs,
      LegalDocumentRepository documents,
      DocumentVersionRepository documentVersions,
      ExtractedDataRepository extractedData,
      EjdRuleBodyParser ruleBodyParser,
      LexiaRulesEngine rulesEngine) {
    this.validationDefs = validationDefs;
    this.ruleDefs = ruleDefs;
    this.caseValidations = caseValidations;
    this.operationDocumentReqs = operationDocumentReqs;
    this.documentRequirements = documentRequirements;
    this.ecdDocumentReqs = ecdDocumentReqs;
    this.documents = documents;
    this.documentVersions = documentVersions;
    this.extractedData = extractedData;
    this.ruleBodyParser = ruleBodyParser;
    this.rulesEngine = rulesEngine;
  }

  @Transactional
  public void refreshForCase(LegalCase legalCase) {
    if (legalCase == null || !CaseWorkflowTypes.isOrchestrated(legalCase.getCaseType())) {
      return;
    }
    UUID tenantId = legalCase.getTenantId();
    UUID caseId = legalCase.getId();
    UUID processId = legalCase.getProcessDefinitionId();
    if (processId == null) {
      return;
    }

    Map<UUID, ValidationDef> defsById =
        validationDefs
            .findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId)
            .stream()
            .collect(Collectors.toMap(ValidationDef::getId, Function.identity()));

    Set<UUID> ruleIds =
        defsById.values().stream()
            .map(ValidationDef::getRuleDefId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, RuleDef> rulesById =
        ruleIds.isEmpty()
            ? Map.of()
            : ruleDefs.findByTenantIdAndIdIn(tenantId, ruleIds).stream()
                .collect(Collectors.toMap(RuleDef::getId, Function.identity()));

    List<CaseValidation> validations =
        caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(caseId, tenantId);
    List<ExtractedData> extractedRows =
        extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(caseId, tenantId);

    for (CaseValidation validation : validations) {
      ValidationDef def =
          validation.getValidationDefId() != null ? defsById.get(validation.getValidationDefId()) : null;
      if (def == null || def.getRuleDefId() == null) {
        continue;
      }
      RuleDef rule = rulesById.get(def.getRuleDefId());
      if (rule == null || !"ACTIVE".equals(rule.getStatus())) {
        continue;
      }
      var parsed = ruleBodyParser.parse(rule);
      switch (rule.getCode()) {
        case RULE_V1, "ECD.V1" -> evaluateDocumentPresence(legalCase, validation, rule);
        case RULE_V2, "ECD.V2" -> evaluateDocumentIntegrity(legalCase, validation, rule);
        case RULE_V3, "ECD.V3" -> evaluateDocumentVigency(legalCase, validation, rule);
        case RULE_V4, "ECD.V4" ->
            applyOutcome(
                validation,
                rule,
                rulesEngine.evaluateInternalConsistency(extractedRows, rule, parsed));
        case RULE_V5 ->
            applyOutcome(
                validation, rule, rulesEngine.evaluateCrossDocument(extractedRows, rule, parsed));
        case RULE_V6 -> applyOutcome(validation, rule, rulesEngine.evaluateHumanLegalReview(rule));
        default -> {}
      }
    }
  }

  private void applyOutcome(
      CaseValidation validation, RuleDef rule, LexiaRulesEngine.EvaluationOutcome outcome) {
    validation.applyEvaluation(outcome.result(), outcome.evidence(), "v" + rule.getVersion());
    caseValidations.save(validation);
  }

  private void evaluateDocumentPresence(LegalCase legalCase, CaseValidation validation, RuleDef rule) {
    if ("ECD".equals(legalCase.getCaseType())) {
      evaluateEcdExecutivePresence(legalCase, validation, rule);
      return;
    }

    List<String> required = resolveRequiredDocumentCodes(legalCase);
    String scopeLabel =
        legalCase.getProductCode() != null && !legalCase.getProductCode().isBlank()
            ? legalCase.getProductCode()
            : legalCase.getOperationTypeCode();

    if (scopeLabel == null || scopeLabel.isBlank()) {
      validation.applyEvaluation(
          "PENDING",
          "Sin producto BIESS ni tipo de operación; no se puede evaluar presencia documental.",
          "v" + rule.getVersion());
      caseValidations.save(validation);
      return;
    }

    if (required.isEmpty()) {
      validation.applyEvaluation(
          "OBSERVATION",
          "No hay documentos requeridos configurados para " + scopeLabel + ".",
          "v" + rule.getVersion());
      caseValidations.save(validation);
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

    List<String> missing =
        required.stream()
            .filter(code -> !present.contains(code.trim().toUpperCase(Locale.ROOT)))
            .toList();

    if (missing.isEmpty()) {
      validation.applyEvaluation(
          "PASS",
          "Documentación requerida presente para " + scopeLabel + ".",
          "v" + rule.getVersion());
    } else {
      validation.applyEvaluation(
          "FAIL",
          "Faltan documentos para " + scopeLabel + ": " + String.join(", ", missing),
          "v" + rule.getVersion());
    }
    caseValidations.save(validation);
  }

  private List<String> resolveRequiredDocumentCodes(LegalCase legalCase) {
    UUID tenantId = legalCase.getTenantId();
    if (legalCase.getProductCode() != null && !legalCase.getProductCode().isBlank()) {
      String product = legalCase.getProductCode().trim().toUpperCase(Locale.ROOT);
      List<String> fromProduct =
          documentRequirements
              .findByTenantIdAndProductCodeOrderBySortOrderAsc(tenantId, product)
              .stream()
              .filter(DocumentRequirement::isMandatory)
              .filter(r -> "ALL".equalsIgnoreCase(r.getCanton()))
              .map(DocumentRequirement::getDocumentTypeCode)
              .map(c -> c.trim().toUpperCase(Locale.ROOT))
              .distinct()
              .toList();
      if (!fromProduct.isEmpty()) {
        return fromProduct;
      }
    }

    String operation = legalCase.getOperationTypeCode();
    if (operation == null || operation.isBlank()) {
      return List.of();
    }
    String op = operation.trim().toUpperCase(Locale.ROOT);
    List<EjdOperationDocumentReq> all =
        operationDocumentReqs.findByTenantIdOrderByOperationCodeAscSortOrderAsc(tenantId);
    List<String> active =
        all.stream()
            .filter(row -> !row.isDeprecated())
            .filter(row -> op.equals(row.getOperationCode()))
            .map(EjdOperationDocumentReq::getDocumentTypeCode)
            .toList();
    if (!active.isEmpty()) {
      return active;
    }
    // Fallback legacy (filas deprecated conservadas)
    return all.stream()
        .filter(row -> op.equals(row.getOperationCode()))
        .map(EjdOperationDocumentReq::getDocumentTypeCode)
        .toList();
  }

  private void evaluateDocumentIntegrity(LegalCase legalCase, CaseValidation validation, RuleDef rule) {
    List<LegalDocument> docs =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            legalCase.getId(), legalCase.getTenantId());
    if (docs.isEmpty()) {
      validation.applyEvaluation(
          "PENDING", "Sin documentos cargados para evaluar integridad.", "v" + rule.getVersion());
      caseValidations.save(validation);
      return;
    }
    List<String> problems = new java.util.ArrayList<>();
    for (LegalDocument document : docs) {
      if (document.getDocType() == null || document.getDocType().isBlank()) {
        problems.add(document.getName() + " (sin tipo)");
        continue;
      }
      var version =
          documentVersions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(
              document.getId(), legalCase.getTenantId());
      if (version.isEmpty()) {
        problems.add(document.getDocType());
        continue;
      }
      String status = version.get().getStatus();
      if (!"PROCESSED".equals(status) && !"STORED".equals(status)) {
        problems.add(document.getDocType() + " (" + status + ")");
      }
    }
    if (problems.isEmpty()) {
      validation.applyEvaluation(
          "PASS", "Documentos con versión almacenada o procesada.", "v" + rule.getVersion());
    } else {
      validation.applyEvaluation(
          "FAIL",
          "Integridad pendiente: " + String.join(", ", problems) + ".",
          "v" + rule.getVersion());
    }
    caseValidations.save(validation);
  }

  private void evaluateEcdExecutivePresence(
      LegalCase legalCase, CaseValidation validation, RuleDef rule) {
    UUID tenantId = legalCase.getTenantId();
    List<String> required =
        ecdDocumentReqs.findByTenantIdOrderBySortOrderAsc(tenantId).stream()
            .map(EcdDocumentReq::getDocumentTypeCode)
            .map(code -> code.trim().toUpperCase(Locale.ROOT))
            .toList();
    if (required.isEmpty()) {
      required = List.of("TITULO_EJECUTIVO");
    }

    Set<String> present = new HashSet<>();
    for (LegalDocument document :
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            legalCase.getId(), tenantId)) {
      if (document.getDocType() != null && !document.getDocType().isBlank()) {
        present.add(document.getDocType().trim().toUpperCase(Locale.ROOT));
      }
    }

    List<String> missing = required.stream().filter(code -> !present.contains(code)).toList();
    if (missing.isEmpty()) {
      validation.applyEvaluation(
          "PASS", "Documentación requerida presente para coactivas.", "v" + rule.getVersion());
    } else {
      validation.applyEvaluation(
          "FAIL",
          "Faltan tipos documentales: " + String.join(", ", missing) + ".",
          "v" + rule.getVersion());
    }
    caseValidations.save(validation);
  }

  private void evaluateDocumentVigency(LegalCase legalCase, CaseValidation validation, RuleDef rule) {
    long typedDocs =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                legalCase.getId(), legalCase.getTenantId())
            .stream()
            .filter(doc -> doc.getDocType() != null && !doc.getDocType().isBlank())
            .count();
    if (typedDocs == 0) {
      validation.applyEvaluation(
          "PENDING", "Sin documentos tipados para evaluar vigencia.", "v" + rule.getVersion());
    } else {
      validation.applyEvaluation(
          "OBSERVATION",
          "Vigencia normativa requiere metadatos de fecha (Document AI); revisión manual recomendada.",
          "v" + rule.getVersion());
    }
    caseValidations.save(validation);
  }
}
