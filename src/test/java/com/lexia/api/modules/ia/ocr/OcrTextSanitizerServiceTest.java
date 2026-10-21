package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OcrTextSanitizerServiceTest {

  private final OcrTextSanitizerService sanitizer = new OcrTextSanitizerService();

  @Test
  void quitaEncabezadoNotarialYConservaDatos() {
    String ruido = "NOTARIA DECIMA DE GUAYAQUIL\nPágina 1 de 40\n\n";
    String cuerpo = "Comparecen el comprador 0912345678 el 12/03/2024. Clave catastral: 09015009001.\n";
    String texto = (ruido + "folio: 12\n").repeat(30) + cuerpo;
    String limpio = sanitizer.limpiar(texto);
    assertTrue(limpio.length() * 2 < texto.length());
    assertTrue(limpio.contains("0912345678"));
    assertTrue(limpio.contains("12/03/2024"));
    assertTrue(limpio.contains("09015009001"));
    assertFalse(limpio.contains("Página 1 de 40"));
  }
}
