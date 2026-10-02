package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActuacionPdfTest {

  @Test
  void generaPdfConTituloYDatos() {
    byte[] pdf = ActuacionPdf.generar("Actuacion", List.of("Juicio: 025-2024", "Deudor: Perez"));
    String texto = new String(pdf, StandardCharsets.ISO_8859_1);
    assertTrue(texto.startsWith("%PDF-1.4"));
    assertTrue(texto.contains("Actuacion"));
    assertTrue(texto.contains("Juicio: 025-2024"));
    assertTrue(texto.contains("%%EOF"));
    assertTrue(texto.contains("startxref"));
  }

  @Test
  void escapaParentesisDelTexto() {
    org.junit.jupiter.api.Assertions.assertEquals("\\(a\\)", ActuacionPdf.escapar("(a)"));
  }
}
