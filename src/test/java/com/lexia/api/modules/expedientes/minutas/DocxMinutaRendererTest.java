package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxMinutaRendererTest {

  private static final Path OUT = Path.of("target", "minutas-test");

  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();
  private final MinutaTemplateCatalog catalog = new MinutaTemplateCatalog();

  @Test
  void renderCompraventaConMapaVacio() {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, "MINUTA_COMPRAVENTA");
    byte[] bytes = renderer.renderVivienda(descriptor, new MinutaViviendaData());
    assertTrue(bytes.length > 500, "DOCX demasiado pequeño: " + bytes.length);
    assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
  }

  @Test
  void renderMutuoConDatosVivienda() {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, "CONTRATO_MUTUO");
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("JUAN PEREZ");
    data.setCedulaConyuge1("0912345678");
    data.setMontoPrestamo("45000.00");
    data.setMontoPrestamoLetras("CUARENTA Y CINCO MIL");
    data.setEstadoCivil("casado");
    byte[] bytes = renderer.renderVivienda(descriptor, data);
    assertTrue(bytes.length > 500);
    assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
  }

  @Test
  void templateMapUsaNodataCuandoFalta() {
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("ANA");
    var map = data.toTemplateMap();
    assertTrue("ANA".equals(map.get("nombre_conyuge_1")));
    assertTrue("nodata".equals(map.get("monto_prestamo")));
    assertTrue("nodata".equals(map.get("estado_civil")));
  }

  @Test
  void catalogoResuelveViviendaTerminadaPreferencial() {
    assertTrue(catalog.supports("VIV_TERMINADA_PREF", "MINUTA_COMPRAVENTA"));
    assertTrue(catalog.supports("viv_terminada_pref", "contrato_mutuo"));
    assertFalse(catalog.supports("VIV_TERMINADA_PREF", "MINUTA_HIPOTECA"));
    assertTrue(
        catalog
            .require("VIV_TERMINADA_PREF", "MINUTA_COMPRAVENTA")
            .classpathResource()
            .startsWith("templates/escrituracion/VIVIENDA TERMINADA PREFERENCIAL/"));
  }

  @Test
  void todasLasPlantillasCarganYSusTagsTienenDato() {
    var keys = new MinutaViviendaData().toTemplateMap().keySet();
    for (MinutaTemplateDescriptor descriptor : catalog.all()) {
      List<String> tags = renderer.tags(descriptor);
      assertFalse(tags.isEmpty(), "Sin tags: " + descriptor.classpathResource());
      for (String tag : tags) {
        assertTrue(keys.contains(tag), descriptor.classpathResource() + " → {{" + tag + "}}");
      }
    }
  }

  @Test
  void preferencialMinutaCompraventaSinPlaceholders() throws Exception {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, "MINUTA_COMPRAVENTA");
    String text = renderYLeer(descriptor, datosCompletos(), "pref_minuta_compraventa.docx");

    assertFalse(text.contains("{{"), "Quedaron placeholders");
    assertFalse(text.contains("nodata"), "Quedaron datos sin resolver");
    assertFalse(text.matches("(?s).*[Xx]{4,}.*"), "Quedaron marcadores XXXX");
    assertTrue(text.contains("V_nombre_vendedor"));
    assertTrue(text.contains("V_clave_catastral"));
    assertTrue(text.contains("USD V_precio_compraventa_numero"));
    assertTrue(text.contains("V_saldo_compraventa_letras DÓLARES"));
    assertTrue(text.contains("V_apoderado_biess"));
  }

  @Test
  void preferencialContratoMutuoSinPlaceholders() throws Exception {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, "CONTRATO_MUTUO");
    MinutaViviendaData data = datosCompletos();
    data.setMontoPrestamo("$85,000.00");
    data.setTasaInteresInicial("5.99%");
    data.setPlazoCredito("300");
    String text = renderYLeer(descriptor, data, "pref_contrato_mutuo.docx");

    assertFalse(text.contains("{{"), "Quedaron placeholders");
    assertFalse(text.contains("nodata"), "Quedaron datos sin resolver");
    assertFalse(text.matches("(?s).*[Xx]{4,}.*"), "Quedaron marcadores XXXX");
    assertTrue(text.contains("USD $ 85,000.00"));
    assertTrue(text.contains("5.99%"));
    assertFalse(text.contains("5.99%%"));
    assertTrue(text.contains("Plazo 300 meses,"));
    assertTrue(text.contains("V_monto_prestamo_letras"));
    assertTrue(text.contains("V_cedula_apoderado_biess"));
  }

  @Test
  void terrenoYViviendaRenderizaAmbasPlantillasSinMarcadores() throws Exception {
    for (String kind : List.of("MINUTA_COMPRAVENTA", "CONTRATO_MUTUO")) {
      MinutaTemplateDescriptor descriptor =
          catalog.require(MinutaTemplateCatalog.PRODUCT_TERRENO_Y_VIVIENDA, kind);
      String text = renderYLeer(descriptor, datosCompletos(), "terreno_" + kind + ".docx");

      assertFalse(text.contains("{{"), kind + ": quedaron placeholders");
      assertFalse(text.contains("nodata"), kind + ": quedaron datos sin resolver");
      assertFalse(text.matches("(?s).*[Xx]{4,}.*"), kind + ": quedaron marcadores XXXX");
      assertTrue(text.contains("V_apoderado_biess"), kind);
      assertTrue(text.contains("V_nombre_conyuge_2"), kind);
    }
  }

  @Test
  void camposPendientesListaSoloTagsDeLaPlantilla() {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, "CONTRATO_MUTUO");
    MinutaViviendaData data = datosCompletos();
    data.setMontoPrestamo("");
    data.setApoderadoBiess("nodata");
    assertEquals(
        List.of("apoderado_biess", "monto_prestamo"),
        renderer.camposPendientes(descriptor, data).stream().sorted().toList());
  }

  @Test
  void plantillaInexistenteFallaConMensajeClaro() {
    MinutaTemplateDescriptor descriptor =
        new MinutaTemplateDescriptor("X", "MINUTA_COMPRAVENTA", "templates/no_existe.docx", "x.docx");
    ApiException e =
        assertThrows(ApiException.class, () -> renderer.renderVivienda(descriptor, null));
    assertTrue(e.getMessage().contains("no_existe.docx"));
  }

  private String renderYLeer(MinutaTemplateDescriptor descriptor, MinutaViviendaData data, String name)
      throws Exception {
    byte[] bytes = renderer.renderVivienda(descriptor, data);
    Files.createDirectories(OUT);
    Files.write(OUT.resolve(name), bytes);
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      return extractor.getText();
    }
  }

  /** Cada campo con el valor {@code V_<tag>} para rastrear dónde cae en el documento. */
  private static MinutaViviendaData datosCompletos() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : new MinutaViviendaData().toTemplateMap().keySet()) {
      values.put(key, "V_" + key);
    }
    return new ObjectMapper().convertValue(values, MinutaViviendaData.class);
  }
}
