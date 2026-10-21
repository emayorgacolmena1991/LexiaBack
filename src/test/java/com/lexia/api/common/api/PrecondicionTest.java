package com.lexia.api.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PrecondicionTest {

  @Test
  void sinTextoOcrTraeMotivoYAccion() {
    ApiException ex =
        Precondicion.fallo(
            "POST analizar-ia",
            "SIN_TEXTO_OCR",
            "Debes ejecutar OCR antes del análisis IA",
            "POST /api/v1/ocr/azure/analyze-single",
            Map.of("expedienteId", "abc"));
    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("PRECONDICION_FALLIDA", ex.getDetails().get("error"));
    assertEquals("SIN_TEXTO_OCR", ex.getDetails().get("motivo"));
    assertEquals("POST /api/v1/ocr/azure/analyze-single", ex.getDetails().get("accionSugerida"));
    assertTrue(ex.getMessage().contains("OCR"));
  }
}
