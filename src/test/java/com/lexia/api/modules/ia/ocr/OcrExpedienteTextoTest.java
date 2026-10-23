package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto.DocOcr;
import java.util.List;
import org.junit.jupiter.api.Test;

class OcrExpedienteTextoTest {

  @Test
  void concatenaTextoPorArchivoSinUnirPdf() {
    String payload =
        OcrExpedienteTexto.documentos(
            List.of(
                new DocOcr("cedula-1", "CEDULA", "cedula.pdf", "JUAN PEREZ 0102030405"),
                new DocOcr("avaluo-2", "AVALUO", "avaluo.pdf", "clave 12345")));

    assertTrue(payload.startsWith("<expediente_ocr>"));
    assertTrue(payload.contains("<documento id=\"cedula-1\" tipo=\"CEDULA\" nombre=\"cedula.pdf\">"));
    assertTrue(payload.contains("<documento id=\"avaluo-2\""));
    assertTrue(payload.contains("JUAN PEREZ"));
    assertTrue(payload.contains("</expediente_ocr>"));
    assertFalse(payload.contains("%PDF"));
  }
}
