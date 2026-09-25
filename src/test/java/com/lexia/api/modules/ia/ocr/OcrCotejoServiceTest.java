package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.OcrCotejoService.DocSection;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Poder]\n:\n: texto B");
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
    assertEquals(1, resp.resumen().coinciden());
    assertFalse(Boolean.TRUE.equals(resp.resumen().observacion()));
  }

  @Test
  void cotejar_diferenciaAcrossDocs() {
    when(cache.getConsolidated("EXP-2"))
        .thenReturn("[Cédula]\n:\n: texto A\n\n[Poder]\n:\n: texto B");
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
    assertEquals("DIFERENCIA", resp.comparaciones().get(0).estado());
    assertEquals(1, resp.resumen().diferencias());
    assertTrue(Boolean.TRUE.equals(resp.resumen().observacion()));
  }
}
