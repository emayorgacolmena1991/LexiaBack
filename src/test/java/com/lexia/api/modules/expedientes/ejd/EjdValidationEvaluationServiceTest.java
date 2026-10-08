package com.lexia.api.modules.expedientes.ejd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.lexia.api.modules.expedientes.caso.CaseValidation;
import com.lexia.api.modules.expedientes.caso.CaseValidationRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentRequirementRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.ecd.EcdDocumentReqRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.reglas.LexiaRulesEngine;
import com.lexia.api.modules.expedientes.reglas.RuleDef;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;
import com.lexia.api.modules.expedientes.proceso.ValidationDef;
import com.lexia.api.modules.expedientes.proceso.ValidationDefRepository;

@ExtendWith(MockitoExtension.class)
class EjdValidationEvaluationServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");
  private static final UUID RULE_ID = UUID.fromString("f5000000-0000-7000-8000-000000000001");
  private static final UUID VALIDATION_DEF_ID = UUID.randomUUID();
  private static final UUID CASE_ID = UUID.randomUUID();

  @Mock private ValidationDefRepository validationDefs;
  @Mock private RuleDefRepository ruleDefs;
  @Mock private CaseValidationRepository caseValidations;
  @Mock private EjdOperationDocumentReqRepository operationDocumentReqs;
  @Mock private DocumentRequirementRepository documentRequirements;
  @Mock private EcdDocumentReqRepository ecdDocumentReqs;
  @Mock private LegalDocumentRepository documents;
  @Mock private DocumentVersionRepository documentVersions;
  @Mock private ExtractedDataRepository extractedData;
  @Mock private EjdRuleBodyParser ruleBodyParser;
  @Mock private LexiaRulesEngine rulesEngine;

  @InjectMocks private EjdValidationEvaluationService evaluationService;

  @Test
  void v1FailsWhenRequiredDocumentMissing() {
    LegalCase legalCase = caseEjd("COMPRAVENTA");
    ValidationDef def = validationDef();
    RuleDef rule = ruleV1();
    CaseValidation caseValidation = caseValidation();

    when(validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(PROCESS_ID, TENANT_ID))
        .thenReturn(List.of(def));
    when(ruleDefs.findByTenantIdAndIdIn(eq(TENANT_ID), any())).thenReturn(List.of(rule));
    when(caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(caseValidation));
    when(operationDocumentReqs.findByTenantIdOrderByOperationCodeAscSortOrderAsc(TENANT_ID))
        .thenReturn(
            List.of(
                row("COMPRAVENTA", "CERT_TRADICION", 1),
                row("COMPRAVENTA", "AVALUO", 2)));
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(document("CERT_TRADICION")));
    when(extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of());
    when(ruleBodyParser.parse(rule)).thenReturn(EjdRuleBodyParser.ParsedRuleBody.manual());

    evaluationService.refreshForCase(legalCase);

    ArgumentCaptor<CaseValidation> saved = ArgumentCaptor.forClass(CaseValidation.class);
    verify(caseValidations).save(saved.capture());
    assertEquals("FAIL", saved.getValue().getResult());
  }

  private static LegalCase caseEjd(String operation) {
    LegalCase legalCase = LegalCase.create(
        TENANT_ID, "LEX-1", "EJD", "Escrituración", "Asunto", "MEDIA", null, UUID.randomUUID(), null);
    legalCase.attachProcess(PROCESS_ID, UUID.randomUUID());
    legalCase.setOperationTypeCode(operation);
    setField(legalCase, "id", CASE_ID);
    return legalCase;
  }

  private static ValidationDef validationDef() {
    ValidationDef def = new ValidationDef();
    setField(def, "id", VALIDATION_DEF_ID);
    setField(def, "ruleDefId", RULE_ID);
    return def;
  }

  private static RuleDef ruleV1() {
    RuleDef rule = new RuleDef();
    setField(rule, "id", RULE_ID);
    setField(rule, "code", "EJD.V1");
    setField(rule, "version", 1);
    setField(rule, "status", "ACTIVE");
    return rule;
  }

  private static CaseValidation caseValidation() {
    return CaseValidation.create(
        TENANT_ID, CASE_ID, VALIDATION_DEF_ID, "V1 Presencia", "Regla", "NOT_RUN", "v1.0");
  }

  private static EjdOperationDocumentReq row(String op, String doc, int order) {
    return EjdOperationDocumentReq.create(TENANT_ID, op, doc, order);
  }

  private static LegalDocument document(String docType) {
    LegalDocument doc = new LegalDocument();
    setField(doc, "docType", docType);
    return doc;
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
