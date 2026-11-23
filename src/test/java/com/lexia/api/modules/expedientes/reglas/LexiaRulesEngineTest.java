package com.lexia.api.modules.expedientes.reglas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lexia.api.modules.expedientes.ejd.EjdRuleBodyParser.ParsedRuleBody;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;

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

  private static ParsedRuleBody interna() {
    return new ParsedRuleBody("internal_consistency", List.of());
  }

  @Test
  void v4QuedaPendienteSiNoHayCamposPorDocumento() {
    List<ExtractedData> rows = List.of(row(null, "comprador.cedula", "0912345678"));

    var outcome = engine.evaluateInternalConsistency(rows, rule("EJD.V4"), interna());

    assertEquals("PENDING", outcome.result());
    assertEquals("No se evaluó: no hay campos extraídos por documento que comparar.", outcome.evidence());
  }

  @Test
  void v4QuedaPendienteSiNingunCampoSeRepite() {
    UUID docId = UUID.randomUUID();
    List<ExtractedData> rows =
        List.of(row(docId, "MATRICULA", "123"), row(docId, "FOLIO", "9"));

    var outcome = engine.evaluateInternalConsistency(rows, rule("EJD.V4"), interna());

    assertEquals("PENDING", outcome.result());
  }

  @Test
  void v4SoloCumpleSiComparoValoresRealesSinDistinguirMayusculas() {
    UUID docId = UUID.randomUUID();
    List<ExtractedData> rows =
        List.of(row(docId, "Matrícula", "abc-1"), row(docId, "MATRICULA", "ABC-1"));

    var outcome = engine.evaluateInternalConsistency(rows, rule("EJD.V4"), interna());

    assertEquals("PASS", outcome.result());
    assertEquals("Sin contradicciones internas: 1 campo(s) comparado(s) en 1 documento(s).", outcome.evidence());
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

  private static ParsedRuleBody cruce() {
    return new ParsedRuleBody("cross_document", List.of("MATRICULA", "FOLIO"));
  }

  private static ExtractedData extraido(String label, String value) {
    return ExtractedData.create(UUID.randomUUID(), UUID.randomUUID(), label, value, "documento");
  }

  @Test
  void v5LeeLaEtiquetaRealPorDocumentoYNombraLosArchivos() {
    List<ExtractedData> rows =
        List.of(
            extraido("000::a::@nombre", "Escritura.pdf"),
            extraido("000::a::matricula", "111"),
            extraido("001::b::@nombre", "Gravamen.pdf"),
            extraido("001::b::numeroMatrícula", "222"));

    var outcome = engine.evaluateCrossDocument(rows, rule("EJD.V5"), cruce());

    assertEquals("FAIL", outcome.result());
    assertEquals(
        "'Número de matrícula' no coincide entre documentos: '111' en 'Escritura.pdf'; '222' en 'Gravamen.pdf'.",
        outcome.evidence());
  }

  @Test
  void v5SinElCampoListaLosDocumentosAnalizados() {
    List<ExtractedData> rows =
        List.of(extraido("000::a::@nombre", "Avaluo.pdf"), extraido("000::a::valor", "100"));

    var outcome = engine.evaluateCrossDocument(rows, rule("EJD.V5"), cruce());

    assertEquals("OBSERVATION", outcome.result());
    assertEquals(
        "No se encontró 'Número de matrícula' ni 'Folio' en los documentos analizados (Avaluo.pdf). Verifique que conste en ellos o complete el dato manualmente.",
        outcome.evidence());
  }

  @Test
  void v5ConUnSoloDocumentoAvisaQueNoHayCotejo() {
    List<ExtractedData> rows =
        List.of(
            extraido("000::a::@nombre", "Escritura.pdf"),
            extraido("000::a::matricula", "111"),
            extraido("000::a::folio", "9"));

    var outcome = engine.evaluateCrossDocument(rows, rule("EJD.V5"), cruce());

    assertEquals("PASS", outcome.result());
    assertEquals(true, outcome.evidence().contains("solo consta en 'Escritura.pdf'"));
  }

  @Test
  void v6CitaLasObservacionesRealesDeLaIaYSigueSinAprobar() {
    var outcome =
        engine.evaluateHumanLegalReview(
            rule("EJD.V6"), List.of("[HIGH] LINDEROS: discrepancia en el lindero norte"));

    assertEquals("PENDING", outcome.result());
    assertEquals(true, outcome.evidence().contains("(alta) LINDEROS: discrepancia en el lindero norte"));
  }

  @Test
  void v6SinDictamenNoAtribuyeNadaALaIa() {
    var outcome = engine.evaluateHumanLegalReview(rule("EJD.V6"), null);

    assertEquals("PENDING", outcome.result());
    assertEquals(true, outcome.evidence().startsWith("Aún no hay dictamen"));
  }

  @Test
  void v6StaysPendingForHumanReview() {
    var outcome = engine.evaluateHumanLegalReview(rule("EJD.V6"), List.of());
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
