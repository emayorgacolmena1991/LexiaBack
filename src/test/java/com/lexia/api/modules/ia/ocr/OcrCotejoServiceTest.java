package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.ia.ocr.OcrCotejoService.DocSection;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoFuente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoGrupo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OcrCotejoServiceTest {

  @Test
  void parseSections_feBracketFormat() {
    String content =
        "[Cédula]\n:\n: Juan Perez CI 010203\n\n[Poder]\n:\n: Juan Pérez CI 010203";
    List<DocSection> sections = OcrCotejoService.parseSections(content, List.of());
    assertEquals(2, sections.size());
    assertEquals("Cédula", sections.get(0).tipo());
    assertTrue(sections.get(0).texto().contains("Juan Perez"));
    assertEquals("Poder", sections.get(1).tipo());
  }

  @Test
  void parseSections_markdownHeadingFormat() {
    String content =
        "# Cédula\nJuan Perez CI 010203\n\n# Papeleta de Votación\nJuan Perez CI 010203";
    List<DocSection> sections = OcrCotejoService.parseSections(content, List.of());
    assertEquals(2, sections.size());
    assertEquals("Cédula", sections.get(0).tipo());
    assertTrue(sections.get(0).texto().contains("Juan Perez"));
    assertEquals("Papeleta de Votación", sections.get(1).tipo());
  }

  @Test
  void parseSections_documentoEqualsFormat() {
    String content =
        "=== DOCUMENTO: CEDULA ===\nJuan Perez\n\n=== DOCUMENTO: PAPELETA ===\nJuan Perez";
    List<DocSection> sections = OcrCotejoService.parseSections(content, List.of());
    assertEquals(2, sections.size());
    assertEquals("CEDULA", sections.get(0).tipo());
    assertTrue(sections.get(0).texto().contains("Juan Perez"));
    assertEquals("PAPELETA", sections.get(1).tipo());
  }

  @Test
  void normalizeValue_ignoresAccentsAndCase() {
    assertEquals(
        OcrCotejoService.normalizeValue("Juan Pérez"),
        OcrCotejoService.normalizeValue("juan perez"));
  }

  @Test
  void agruparComparaciones_consolidaLinderosYOrdenaGrupos() {
    List<CotejoFuente> fuentes =
        List.of(new CotejoFuente("Cédula", "a"), new CotejoFuente("Escritura", "b"));
    List<CotejoComparacion> flat =
        List.of(
            new CotejoComparacion(
                "lindero_norte", "Norte", "DISCREPANCIA", "Cédula ↔ Escritura", null, "discrepa", fuentes),
            new CotejoComparacion(
                "lindero_sur", "Sur", "COINCIDE", "Cédula ↔ Escritura", "Calle 1", "ok", fuentes),
            new CotejoComparacion(
                "lindero_este", "Este", "COINCIDE", "Cédula ↔ Escritura", "Río", "ok", fuentes),
            new CotejoComparacion(
                "lindero_oeste", "Oeste", "REVISAR", "Cédula ↔ Escritura", "Vía", "solo uno", fuentes),
            new CotejoComparacion(
                "nombreCompleto", "Nombres y apellidos", "COINCIDE", "Cédula ↔ Escritura", "Ana", "ok", fuentes),
            new CotejoComparacion(
                "matriculaInmobiliaria", "Matrícula inmobiliaria", "COINCIDE", "Cédula ↔ Escritura", "123", "ok", fuentes),
            new CotejoComparacion(
                "fechaEmision", "Fecha de emisión", "COINCIDE", "Cédula ↔ Escritura", "2026", "ok", fuentes),
            new CotejoComparacion(
                "numeroCertificado", "Número de certificado", "COINCIDE", "Cédula ↔ Escritura", "10", "ok", fuentes));

    List<CotejoGrupo> grupos = OcrCotejoService.agruparComparaciones(flat);
    assertEquals(5, grupos.size());
    assertEquals("identidad", grupos.get(0).id());
    assertEquals("inmueble", grupos.get(1).id());
    assertEquals("linderos", grupos.get(2).id());
    assertEquals("Linderos del inmueble", grupos.get(2).label());
    assertEquals(4, grupos.get(2).comparaciones().size());
    assertEquals("DISCREPANCIA", grupos.get(2).estado());
    assertTrue(grupos.get(2).resumen().contains("diferencias"));
    assertEquals("vigencia", grupos.get(3).id());
    assertEquals("otros", grupos.get(4).id());
    assertTrue(grupos.size() <= 6);
  }

  @Test
  void clasificarGrupo_superficieNoEsIdentidad() {
    assertEquals("inmueble", OcrCotejoService.clasificarGrupo("superficie", "Superficie"));
    assertEquals("identidad", OcrCotejoService.clasificarGrupo("cedula", "Cédula"));
    assertEquals("linderos", OcrCotejoService.clasificarGrupo("lindero_norte", "Lindero norte"));
  }

  @Test
  void resolverConcepto_agrupaEquivalentesIdentidad() {
    assertEquals("nombreCompleto", OcrCotejoService.resolverConcepto("nombres").id());
    assertEquals("nombreCompleto", OcrCotejoService.resolverConcepto("apellidos").id());
    assertEquals("nombreCompleto", OcrCotejoService.resolverConcepto("nombreCompleto").id());
    assertEquals("identificacion", OcrCotejoService.resolverConcepto("nui").id());
    assertEquals("identificacion", OcrCotejoService.resolverConcepto("cedula").id());
  }

  @Test
  void normalizeLindero_unificaUnidades() {
    assertEquals(
        OcrCotejoService.normalizeLindero("solar 7 con 10.40 metros"),
        OcrCotejoService.normalizeLindero("solar 7 con 10.40 mts"));
    assertTrue(
        !OcrCotejoService.normalizeLindero("solar 7 con 10.40 metros")
            .equals(OcrCotejoService.normalizeLindero("solar 8 con 10.40 metros")));
  }

  @Test
  void fechaComparable_formatosEquivalentes() {
    assertEquals("2025-05-02", OcrCotejoService.fechaComparable("02/MAY/2025"));
    assertEquals("2025-05-02", OcrCotejoService.fechaComparable("02/05/2025"));
  }

  @Test
  void clasificarFamiliaDocumento_reglasCotejoNotarial() {
    assertEquals(
        OcrCotejoService.DocFamilia.IDENTIDAD,
        OcrCotejoService.clasificarFamiliaDocumento("Cédula", "cedula.png"));
    assertEquals(
        OcrCotejoService.DocFamilia.IDENTIDAD,
        OcrCotejoService.clasificarFamiliaDocumento("Papeleta", "papeleta_votacion.pdf"));
    assertEquals(
        OcrCotejoService.DocFamilia.INMUEBLE,
        OcrCotejoService.clasificarFamiliaDocumento("Avalúo", "avaluo_municipal.png"));
    assertEquals(
        OcrCotejoService.DocFamilia.INMUEBLE,
        OcrCotejoService.clasificarFamiliaDocumento("Historia de Dominio", "historia_dominio.png"));
    assertEquals(
        OcrCotejoService.DocFamilia.OTRO,
        OcrCotejoService.clasificarFamiliaDocumento("Poder", "poder.pdf"));
  }

  @Test
  void aplanarDatos_extraeLinderosAnidados() {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    OcrCotejoService.aplanarDatos(
        "",
        Map.of(
            "linderos",
            Map.of(
                "norte", "Calle Principal",
                "sur", "Río Guayas",
                "este", "Predio X",
                "oeste", "Vía pública")),
        out);
    Map<String, String> normalizados = new java.util.LinkedHashMap<>();
    out.forEach((k, v) -> OcrCotejoService.incorporarCampo(normalizados, k, v));
    assertEquals("Calle Principal", normalizados.get("lindero_norte"));
    assertEquals("Río Guayas", normalizados.get("lindero_sur"));
    assertEquals("Predio X", normalizados.get("lindero_este"));
    assertEquals("Vía pública", normalizados.get("lindero_oeste"));
  }

  @Test
  void parseLinderosCompuestos_parteTextoUnico() {
    Map<String, String> parts =
        OcrCotejoService.parseLinderosCompuestos(
            "Norte: Av. Amazonas Sur: Calle 10 Este: Río Oeste: Propiedad de Pérez");
    assertEquals("Av. Amazonas", parts.get("lindero_norte"));
    assertEquals("Calle 10", parts.get("lindero_sur"));
    assertEquals("Río", parts.get("lindero_este"));
    assertEquals("Propiedad de Pérez", parts.get("lindero_oeste"));
  }

  @Test
  void resumenGrupo_linderosCuentaDiferencias() {
    List<CotejoFuente> fuentes =
        List.of(
            new CotejoFuente("historia_dominio.png", "Calle Principal"),
            new CotejoFuente("avaluo.png", "Otra calle"));
    List<CotejoComparacion> items =
        List.of(
            new CotejoComparacion(
                "lindero_norte", "Norte", "DISCREPANCIA", "historia ↔ avaluo", null, "discrepa", fuentes),
            new CotejoComparacion(
                "lindero_sur", "Sur", "COINCIDE", "historia ↔ avaluo", "Río Guayas", "Coincide", fuentes),
            new CotejoComparacion(
                "lindero_este", "Este", "DISCREPANCIA", "historia ↔ avaluo", null, "discrepa", fuentes),
            new CotejoComparacion(
                "lindero_oeste", "Oeste", "DISCREPANCIA", "historia ↔ avaluo", null, "discrepa", fuentes));
    String resumen = OcrCotejoService.resumenGrupo("linderos", items);
    assertEquals("3 de 4 linderos presentan diferencias", resumen);
  }

  @Test
  void motivoDiscrepancia_listaDocumentosYValores() {
    String motivo =
        OcrCotejoService.motivoDiscrepancia(
            Map.of("cedula.png", "ANA", "papeleta.png", "LUIS"));
    assertTrue(motivo.contains("Discrepancia"));
    assertTrue(motivo.contains("ANA") || motivo.contains("LUIS"));
  }
}
