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
  void renderMinutaHipotecaConMapaVacio() {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "MINUTA_HIPOTECA");
    byte[] bytes = renderer.renderVivienda(descriptor, new MinutaViviendaData());
    assertTrue(bytes.length > 500, "DOCX demasiado pequeño: " + bytes.length);
    assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
  }

  @Test
  void renderMutuoConDatosVivienda() {
    MinutaTemplateDescriptor descriptor =
        catalog.require(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "CONTRATO_MUTUO");
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
  void sustitucionUsaSusPlantillasYHipotecadaNoUsaLasDeSustitucion() {
    String dir = "templates/escrituracion/SUSTITUCION DE HIPOTECA/";
    assertEquals(
        dir + "minuta_hipoteca.docx",
        catalog.require("SUSTITUCION_HIPOTECA", "MINUTA_HIPOTECA").classpathResource());
    assertEquals(
        dir + "contrato_sustitucion_hipoteca.docx",
        catalog.require("SUSTITUCION_HIPOTECA", "CONTRATO_MUTUO").classpathResource());

    for (String kind : List.of("MINUTA_COMPRAVENTA", "CONTRATO_MUTUO")) {
      String resource = catalog.require("VIV_HIPOTECADA_BIESS", kind).classpathResource();
      assertTrue(resource.contains("/VIVIENDA HIPOTECADA BIESS/"), resource);
    }
    ApiException e =
        assertThrows(
            ApiException.class, () -> catalog.require("VIV_TERMINADA_IND", "CONTRATO_MUTUO"));
    assertTrue(e.getMessage().contains("aún no es generable"), e.getMessage());
    for (MinutaTemplateDescriptor d : catalog.sinConectar()) {
      assertFalse(d.classpathResource().startsWith(dir), d.classpathResource());
    }
  }

  @Test
  void viviendaHipotecadaRenderizaAmbasPlantillasSinPlaceholders() throws Exception {
    for (String kind : List.of("MINUTA_COMPRAVENTA", "CONTRATO_MUTUO")) {
      MinutaTemplateDescriptor descriptor =
          catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, kind);
      String text = renderYLeer(descriptor, datosCompletos(), "hipotecada_" + kind + ".docx");
      assertFalse(text.contains("{{"), kind + ": quedaron placeholders");
      assertFalse(text.contains("nodata"), kind + ": quedaron datos sin resolver");
    }
  }

  @Test
  void todasLasPlantillasCarganYSusTagsTienenDato() {
    for (MinutaTemplateDescriptor descriptor : catalog.all()) {
      assertFalse(
          renderer.tags(descriptor).isEmpty(), "Sin tags: " + descriptor.classpathResource());
      assertEquals(
          List.of(), renderer.tagsSinResolver(descriptor), descriptor.classpathResource());
    }
  }

  @Test
  void plantillasSinConectarCarganYReportanTagsSinResolver() {
    for (MinutaTemplateDescriptor descriptor : catalog.sinConectar()) {
      assertFalse(
          renderer.tags(descriptor).isEmpty(), "Sin tags: " + descriptor.classpathResource());
      System.out.println(
          descriptor.productCode()
              + " "
              + descriptor.templateKind()
              + " sin resolver: "
              + renderer.tagsSinResolver(descriptor));
    }
  }

  @Test
  void aliasesApuntanACamposCanonicosYNoLosRedefinen() {
    var campos = new MinutaViviendaData().toTemplateMap().keySet();
    MinutaTagAliases.aliases()
        .forEach(
            (tag, campo) -> {
              assertTrue(campos.contains(campo), tag + " → campo inexistente " + campo);
              assertFalse(campos.contains(tag), "Alias redefine un campo canónico: " + tag);
            });
  }

  @Test
  void aliasResuelveValorDelCampoCanonico() {
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("ANA");
    data.setClaveCatastral("09-01-001");
    data.setPlazoCredito("300");
    data.setInstitucionFinancieraOriginal("BANCO X");
    var valores =
        MinutaTagAliases.valoresPorTag(
            List.of(
                "nombre_deudor_1",
                "nombre_comprador",
                "codigo_catastral",
                "plazo_meses",
                "banco_hipoteca_anterior",
                "precio_venta_numeral"),
            data.toTemplateMap());
    assertEquals("ANA", valores.get("nombre_deudor_1"));
    assertEquals("ANA", valores.get("nombre_comprador"));
    assertEquals("09-01-001", valores.get("codigo_catastral"));
    assertEquals("300", valores.get("plazo_meses"));
    assertEquals("300 meses", valores.get("plazo_credito"));
    assertEquals("BANCO X", valores.get("banco_hipoteca_anterior"));
    assertEquals("nodata", valores.get("precio_venta_numeral"));
  }

  @Test
  void contratoTerminadaSolidariaSeRenderizaSoloConAliases() throws Exception {
    MinutaTemplateDescriptor descriptor =
        catalog.sinConectar().stream()
            .filter(
                d ->
                    d.productCode().equals(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID)
                        && d.templateKind().equals("CONTRATO_MUTUO"))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of(), renderer.tagsSinResolver(descriptor));

    MinutaViviendaData data = datosCompletos();
    data.setPlazoCredito("300");
    String text = renderYLeer(descriptor, data, "solidaria_contrato_mutuo.docx");

    assertFalse(text.contains("{{"), "Quedaron placeholders");
    assertFalse(text.contains("nodata"), "Quedaron datos sin resolver");
    assertTrue(text.contains("V_nombre_conyuge_1"));
    assertTrue(text.contains("V_cedula_conyuge_2"));
    assertTrue(text.contains("V_nombre_afiliado"));
    assertTrue(text.contains("Plazo 300 meses,"));
    assertFalse(text.contains("meses meses"));
  }

  @Test
  void camposPendientesReportaCampoCanonicoDeLosAliases() {
    MinutaTemplateDescriptor descriptor =
        catalog.sinConectar().stream()
            .filter(d -> d.classpathResource().endsWith("contrato_vivienda_terminada.docx"))
            .findFirst()
            .orElseThrow();
    MinutaViviendaData data = datosCompletos();
    data.setNombreConyuge1("");
    data.setPlazoCredito("");
    assertEquals(
        List.of("nombre_conyuge_1", "plazo_credito"),
        renderer.camposPendientes(descriptor, data).stream().sorted().toList());
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
