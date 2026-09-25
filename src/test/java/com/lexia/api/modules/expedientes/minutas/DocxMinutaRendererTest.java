package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocxMinutaRendererTest {

  @Test
  void renderCompraventaConMapaVacio() {
    DocxMinutaRenderer renderer = new DocxMinutaRenderer();
    MinutaTemplateDescriptor descriptor =
        new MinutaTemplateDescriptor(
            MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS,
            "MINUTA_COMPRAVENTA",
            "templates/MINUTA_HIPOTECA_TEMPLATE.docx",
            "out.docx");
    byte[] bytes = renderer.renderVivienda(descriptor, new MinutaViviendaData());
    assertTrue(bytes.length > 500, "DOCX demasiado pequeño: " + bytes.length);
    // ZIP magic
    assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
  }

  @Test
  void renderMutuoConDatosVivienda() {
    DocxMinutaRenderer renderer = new DocxMinutaRenderer();
    MinutaTemplateDescriptor descriptor =
        new MinutaTemplateDescriptor(
            MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS,
            "CONTRATO_MUTUO",
            "templates/MINUTA_HIPOTECA_TEMPLATE.docx",
            "out.docx");
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("JUAN PEREZ");
    data.setCedulaConyuge1("0912345678");
    byte[] bytes = renderer.renderVivienda(descriptor, data);
    assertTrue(bytes.length > 500);
    assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
  }
}
