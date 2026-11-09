package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import org.junit.jupiter.api.Test;

class ExpedienteSeguimientoEvidenciaTest {

  private static DocumentoOcrResultadoDTO ocr(String estado, String motivo, Integer confianza) {
    return new DocumentoOcrResultadoDTO(
        "d1", "Escritura.pdf", "ESCRITURA", "texto", "{\"crudo\":true}", estado, motivo, confianza);
  }

  @Test
  void usaElMotivoDeLaIaYSuConfianza() {
    assertEquals(
        "La IA indica sobre 'Escritura.pdf' (confianza 72 %): sello ilegible en la última página",
        ExpedienteSeguimientoService.evidenciaIa(
            ocr("DUDOSO", "sello ilegible en la última página", 72)));
  }

  @Test
  void sinMotivoNuncaMuestraElJsonCrudo() {
    String evidencia = ExpedienteSeguimientoService.evidenciaIa(ocr("ILEGIBLE", null, null));

    assertEquals("La IA no pudo leer 'Escritura.pdf'; revise el archivo.", evidencia);
    assertFalse(evidencia.contains("crudo"));
  }
}
