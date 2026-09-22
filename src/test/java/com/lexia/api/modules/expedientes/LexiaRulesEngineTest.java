package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lexia.api.modules.expedientes.EjdRuleBodyParser.ParsedRuleBody;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LexiaRulesEngineTest {

  private final LexiaRulesEngine engine = new LexiaRulesEngine();

  @Test
  void v4FailsWhenSameDocumentHasConflictingValues() {
    UUID docId = UUID.randomUUID();
    List<ExtractedData> rows =
        List.of(row(docId, "MATRICULA", "123"), row(docId, "MATRICULA", "456"));

    var outcome =
        engine.evaluateInternalConsistency(rows, rule("EJD.V4"), new ParsedRuleBody("internal_consistency", List.of()));

    assertEquals("FAIL", outcome.result());
  }

  @Test
  void v5FailsWhenMatriculaDiffersAcrossDocuments() {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    List<ExtractedData> rows =
        List.of(row(docA, "MATRICULA", "111"), row(docB, "MATRICULA", "222"));

    var outcome =
        engine.evaluateCrossDocument(
            rows,
            rule("EJD.V5"),
            new ParsedRuleBody("cross_document", List.of("MATRICULA", "FOLIO")));

    assertEquals("FAIL", outcome.result());
  }

  @Test
  void v6StaysPendingForHumanReview() {
    var outcome = engine.evaluateHumanLegalReview(rule("EJD.V6"));
    assertEquals("PENDING", outcome.result());
  }

  private static ExtractedData row(UUID documentId, String label, String value) {
    ExtractedData row = new ExtractedData();
    setField(row, "documentId", documentId);
    setField(row, "fieldLabel", label);
    setField(row, "fieldValue", value);
    return row;
  }

  private static RuleDef rule(String code) {
    RuleDef rule = new RuleDef();
    setField(rule, "code", code);
    setField(rule, "version", 1);
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
