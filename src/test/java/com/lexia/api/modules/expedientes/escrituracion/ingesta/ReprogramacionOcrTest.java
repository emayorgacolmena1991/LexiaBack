package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReprogramacionOcrTest {

  @Test
  void sinOcrReprogramaHastaDiez() {
    String msg = "Sin texto OCR para analizar. Ejecuta Azure OCR";
    assertTrue(IngestaJobWorker.sinOcrRecuperable("IA_ANALISIS", msg));
    assertFalse(IngestaJobWorker.sinOcrRecuperable("OCR_EXTRACTION", msg));
    assertFalse(IngestaJobWorker.agotado(3));
    assertTrue(IngestaJobWorker.agotado(10));
  }
}
