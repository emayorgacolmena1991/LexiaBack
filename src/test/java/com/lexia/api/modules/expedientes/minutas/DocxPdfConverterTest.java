package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocxPdfConverterTest {

  @Test
  void convierteMinutaRenderizadaAPdf() {
    DocxMinutaRenderer renderer = new DocxMinutaRenderer();
    MinutaTemplateCatalog catalog = new MinutaTemplateCatalog();
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("JUAN PEREZ");
    data.setMontoPrestamo("45000.00");
    byte[] docx =
        renderer.renderVivienda(
            catalog.require(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "CONTRATO_MUTUO"),
            data);

    byte[] pdf = new DocxPdfConverter("", "target/minutas-test").toPdf(docx);

    assertTrue(pdf.length > 1000, "PDF demasiado pequeño: " + pdf.length);
    assertTrue(new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF-"));
  }
}
