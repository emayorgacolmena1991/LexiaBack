package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.OcrCotejoService.DocSection;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoFuente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoGrupo;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

class OcrCotejoServiceTest {

  private OcrSessionCacheService cache;
  private AnalisisDocumentoService analisis;
  private OcrCotejoService service;

  @BeforeEach
  void setUp() {
    cache = mock(OcrSessionCacheService.class);
    analisis = mock(AnalisisDocumentoService.class);
    service = new OcrCotejoService(cache, analisis);
  }

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
  void cotejar_coincideAcrossDocs() {
    when(cache.getConsolidated("EXP-1"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Papeleta]\n:\n: texto B");
    when(cache.listResults("EXP-1")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(tipo, "ok", Map.of("nombre", "Juan Perez")));
            });

    CotejoResponse resp = service.cotejar("EXP-1");
    assertEquals(1, resp.comparaciones().size());
    assertEquals("COINCIDE", resp.comparaciones().get(0).estado());
    assertEquals("nombreCompleto", resp.comparaciones().get(0).campo());
    assertEquals("Nombres y apellidos", resp.comparaciones().get(0).label());
    assertEquals(1, resp.resumen().coinciden());
    assertFalse(Boolean.TRUE.equals(resp.resumen().observacion()));
    assertTrue(StringUtils.hasText(resp.resumen().observacionGeneral()));
    assertEquals("Juan Perez", resp.comparaciones().get(0).valor());
    assertEquals(2, resp.comparaciones().get(0).fuentes().size());
    assertEquals(1, resp.grupos().size());
    assertEquals("identidad", resp.grupos().get(0).id());
    assertEquals("COINCIDE", resp.grupos().get(0).estado());
  }

  @Test
  void cotejar_diferenciaAcrossDocs() {
    when(cache.getConsolidated("EXP-2"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Papeleta]\n:\n: texto B");
    when(cache.listResults("EXP-2")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              String nombre = "Cédula".equals(tipo) ? "Ana" : "Luis";
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(tipo, "ok", Map.of("nombre", nombre)));
            });

    CotejoResponse resp = service.cotejar("EXP-2");
    assertEquals("DISCREPANCIA", resp.comparaciones().get(0).estado());
    assertEquals(1, resp.resumen().diferencias());
    assertTrue(Boolean.TRUE.equals(resp.resumen().observacion()));
    assertTrue(StringUtils.hasText(resp.resumen().observacionGeneral()));
    assertEquals("Ana", resp.comparaciones().get(0).fuentes().get(0).valor());
    assertEquals("Luis", resp.comparaciones().get(0).fuentes().get(1).valor());
    assertTrue(
        resp.comparaciones().get(0).motivo().toLowerCase().contains("nombres")
            || resp.comparaciones().get(0).motivo().toLowerCase().contains("persona"));
    assertEquals("DISCREPANCIA", resp.grupos().get(0).estado());
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
  void cotejar_mergeNombreYCedulaConceptos() {
    when(cache.getConsolidated("EXP-ID"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Papeleta]\n:\n: texto B");
    when(cache.listResults("EXP-ID")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              if ("Cédula".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        tipo,
                        "ok",
                        Map.of(
                            "nombres", "REINA MARITZA",
                            "apellidos", "TORRES ALVARADO",
                            "cedula", "0912345678")));
              }
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(
                      tipo,
                      "ok",
                      Map.of(
                          "nombreCompleto", "FRANKLIN ALEJANDRO CORREA MALDONADO",
                          "identificacion", "0923456789")));
            });

    CotejoResponse resp = service.cotejar("EXP-ID");
    assertEquals(1, resp.grupos().size());
    assertEquals("identidad", resp.grupos().get(0).id());
    assertEquals(2, resp.grupos().get(0).comparaciones().size());
    assertEquals("DISCREPANCIA", resp.grupos().get(0).estado());

    CotejoComparacion nombres =
        resp.comparaciones().stream()
            .filter(c -> "nombreCompleto".equals(c.campo()))
            .findFirst()
            .orElseThrow();
    assertEquals("DISCREPANCIA", nombres.estado());
    assertEquals(2, nombres.fuentes().size());
    assertTrue(nombres.motivo().toLowerCase().contains("persona"));

    CotejoComparacion id =
        resp.comparaciones().stream()
            .filter(c -> "identificacion".equals(c.campo()))
            .findFirst()
            .orElseThrow();
    assertEquals("DISCREPANCIA", id.estado());
  }

  @Test
  void cotejar_noMarcaRevisarPorCampoSoloSinPar() {
    when(cache.getConsolidated("EXP-SOLO"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Papeleta]\n:\n: texto B");
    when(cache.listResults("EXP-SOLO")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              if ("Cédula".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        tipo, "ok", Map.of("nombre", "Ana", "observacionLibre", "solo aqui")));
              }
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(tipo, "ok", Map.of("nombre", "Ana")));
            });

    CotejoResponse resp = service.cotejar("EXP-SOLO");
    assertEquals(1, resp.comparaciones().size());
    assertEquals("COINCIDE", resp.comparaciones().get(0).estado());
    assertTrue(resp.comparaciones().stream().noneMatch(c -> "REVISAR".equals(c.estado())));
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
  void cotejar_noCruzaIdentidadConHistoriaDominio() {
    when(cache.getConsolidated("EXP-X"))
        .thenReturn(
            "[Cédula]\n:\n: a\n\n[Papeleta]\n:\n: b\n\n[Historia de Dominio]\n:\n: c\n\n[Avalúo]\n:\n: d");
    when(cache.listResults("EXP-X")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              if ("Cédula".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        tipo, "ok", Map.of("nombre", "ANA TORRES", "cedula", "0911111111")));
              }
              if ("Papeleta".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        tipo, "ok", Map.of("nombreCompleto", "ANA TORRES", "identificacion", "0911111111")));
              }
              if ("Historia de Dominio".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        tipo,
                        "ok",
                        Map.of(
                            "cedula", "0999999999",
                            "propietario", "OTRO DUEÑO",
                            "codigoCatastral", "93-0066-006-0000-0-0")));
              }
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(
                      tipo,
                      "ok",
                      Map.of(
                          "propietario", "OTRO DUEÑO",
                          "codigoCatastral", "061-0615-008-0-0-0-1")));
            });

    CotejoResponse resp = service.cotejar("EXP-X");

    CotejoGrupo identidad =
        resp.grupos().stream().filter(g -> "identidad".equals(g.id())).findFirst().orElseThrow();
    for (CotejoComparacion c : identidad.comparaciones()) {
      assertEquals(2, c.fuentes().size());
      assertTrue(
          c.fuentes().stream().noneMatch(f -> f.documento().toLowerCase().contains("historia")),
          "Identidad no debe incluir Historia de Dominio: " + c.relacion());
      assertTrue(
          c.fuentes().stream().noneMatch(f -> f.documento().toLowerCase().contains("aval")),
          "Identidad no debe incluir Avalúo: " + c.relacion());
    }
    assertEquals("COINCIDE", identidad.estado());

    CotejoGrupo inmueble =
        resp.grupos().stream().filter(g -> "inmueble".equals(g.id())).findFirst().orElseThrow();
    CotejoComparacion catastro =
        inmueble.comparaciones().stream()
            .filter(c -> "codigoCatastral".equals(c.campo()))
            .findFirst()
            .orElseThrow();
    assertEquals("DISCREPANCIA", catastro.estado());
    assertEquals(2, catastro.fuentes().size());
    assertTrue(
        catastro.fuentes().stream().noneMatch(f -> f.documento().toLowerCase().contains("cedula")
            || f.documento().equalsIgnoreCase("Cédula")
            || f.documento().equalsIgnoreCase("Papeleta")));
    assertTrue(StringUtils.hasText(catastro.fuentes().get(0).valor()));
    assertTrue(StringUtils.hasText(catastro.fuentes().get(1).valor()));
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
                "lindero_norte",
                "Norte",
                "DISCREPANCIA",
                "historia ↔ avaluo",
                null,
                "discrepa",
                fuentes),
            new CotejoComparacion(
                "lindero_sur",
                "Sur",
                "COINCIDE",
                "historia ↔ avaluo",
                "Río Guayas",
                "Coincide",
                fuentes),
            new CotejoComparacion(
                "lindero_este",
                "Este",
                "DISCREPANCIA",
                "historia ↔ avaluo",
                null,
                "discrepa",
                fuentes),
            new CotejoComparacion(
                "lindero_oeste",
                "Oeste",
                "DISCREPANCIA",
                "historia ↔ avaluo",
                null,
                "discrepa",
                fuentes));
    String resumen = OcrCotejoService.resumenGrupo("linderos", items);
    assertEquals("3 de 4 linderos presentan diferencias", resumen);
  }

  @Test
  void cotejar_coincideEntreDocsQueTienenElCampo() {
    when(cache.getConsolidated("EXP-3"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Papeleta]\n:\n: texto B");
    when(cache.listResults("EXP-3")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(tipo, "ok", Map.of("nombre", "Ana Pérez")));
            });

    CotejoResponse resp = service.cotejar("EXP-3");
    assertEquals("COINCIDE", resp.comparaciones().get(0).estado());
    assertTrue(resp.comparaciones().get(0).motivo().toLowerCase().contains("coincide"));
    assertEquals(2, resp.comparaciones().get(0).fuentes().size());
  }

  @Test
  void cotejar_poderNoParticipaComoParDeIdentidad() {
    when(cache.getConsolidated("EXP-PODER"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Poder]\n:\n: texto B");
    when(cache.listResults("EXP-PODER")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              String nombre = "Cédula".equals(tipo) ? "Ana" : "Luis";
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(tipo, "ok", Map.of("nombre", nombre)));
            });

    CotejoResponse resp = service.cotejar("EXP-PODER");
    assertTrue(
        resp.comparaciones().isEmpty(),
        "Sin Papeleta no debe inventar discrepancia Cédula↔Poder");
  }

  @Test
  void motivoDiscrepancia_listaDocumentosYValores() {
    String motivo =
        OcrCotejoService.motivoDiscrepancia(
            Map.of("cedula.png", "ANA", "papeleta.png", "LUIS"));
    assertTrue(motivo.contains("Discrepancia"));
    assertTrue(motivo.contains("ANA") || motivo.contains("LUIS"));
  }

  @Test
  void cotejar_linderosNormalizaMetros() {
    when(cache.getConsolidated("EXP-L"))
        .thenReturn("[Avalúo]\n:\n: texto A\n\n[Historia de Dominio]\n:\n: texto B");
    when(cache.listResults("EXP-L")).thenReturn(List.of());
    when(analisis.isConfigured()).thenReturn(true);
    when(analisis.extraerDatosClave(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String tipo = inv.getArgument(1);
              String unidad = "Avalúo".equals(tipo) ? "metros" : "mts";
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(
                      tipo,
                      "ok",
                      Map.of(
                          "lindero_norte",
                          "solar 7 con 10.40 " + unidad,
                          "lindero_sur",
                          "Calle 10",
                          "lindero_este",
                          "Río",
                          "lindero_oeste",
                          "Vía")));
            });

    CotejoResponse resp = service.cotejar("EXP-L");
    CotejoGrupo linderos =
        resp.grupos().stream().filter(g -> "linderos".equals(g.id())).findFirst().orElseThrow();
    CotejoComparacion norte =
        linderos.comparaciones().stream()
            .filter(c -> "lindero_norte".equals(c.campo()))
            .findFirst()
            .orElseThrow();
    assertEquals("COINCIDE", norte.estado());
  }
}
