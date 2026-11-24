package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** Vivienda Terminada Individual/Solidaria con uno o dos compradores (secciones poi-tl). */
class CompradoresMinutaTest {

  private static final List<String> SEGUNDO =
      List.of("nombre_conyuge_2", "cedula_conyuge_2", "profesion_conyuge_2");

  private final MinutaTemplateCatalog catalog = new MinutaTemplateCatalog();
  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();

  @Test
  void individualConUnCompradorRedactaEnSingularYSinNodata() throws Exception {
    MinutaTemplateDescriptor minuta =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, "MINUTA_COMPRAVENTA");
    String texto = render(minuta, datos(false, "Soltero"));

    assertFalse(texto.contains("nodata"), "quedaron datos sin resolver");
    assertFalse(texto.contains("{{"), "quedaron tags o secciones");
    assertTrue(texto.contains("B) El/La señor(a) V_nombre_conyuge_1"));
    assertTrue(texto.contains("a quien en adelante se le denominará como \"PARTE COMPRADORA\""));
    assertTrue(texto.contains("Dos) Por otra parte comparece el/la señor(a) V_nombre_conyuge_1"));
    assertFalse(texto.contains("Los cónyuges el/la señor(a) V_nombre_conyuge_1"), "plural con un comprador");
    assertFalse(texto.contains("comparecen los cónyuges"), "plural con un comprador");
    assertFalse(texto.contains("V_profesion_conyuge_1 y"), "profesiones en plural");
    // Los vendedores no cambian: su párrafo sigue en plural.
    assertTrue(texto.contains("A) Los cónyuges el/la señor(a) V_nombre_vendedor"));
    // Los párrafos con los tags de sección desaparecen: «B)» va justo después de «A)».
    List<String> parrafos = parrafos(renderer.renderVivienda(minuta, datos(false, "Soltero")));
    int a = indice(parrafos, "A) Los cónyuges");
    assertTrue(parrafos.get(a + 1).startsWith("B) El/La señor(a)"), parrafos.get(a + 1));
  }

  private static List<String> parrafos(byte[] docx) throws Exception {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
      return doc.getParagraphs().stream().map(p -> p.getText()).toList();
    }
  }

  private static int indice(List<String> parrafos, String prefijo) {
    for (int i = 0; i < parrafos.size(); i++) {
      if (parrafos.get(i).contains(prefijo)) {
        return i;
      }
    }
    throw new AssertionError("No está el párrafo «" + prefijo + "»");
  }

  @Test
  void individualYSolidariaConDosCompradoresMantienenLaRedaccionDeConyuges() throws Exception {
    for (String producto :
        List.of(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID)) {
      String texto = render(catalog.require(producto, "MINUTA_COMPRAVENTA"), datos(true, "Casados"));
      assertFalse(texto.contains("nodata"), producto);
      assertTrue(texto.contains("B) Los cónyuges el/la señor(a) V_nombre_conyuge_1"), producto);
      assertTrue(texto.contains("V_nombre_conyuge_2"), producto);
      assertTrue(texto.contains("comparecen los cónyuges el/la señor(a) V_nombre_conyuge_1"), producto);
      assertFalse(texto.contains("B) El/La señor(a)"), producto + ": quedó también el singular");
    }
  }

  @Test
  void contratoDeMutuoConUnDeudorSinSegundaFirma() throws Exception {
    MinutaTemplateDescriptor contrato =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID, "CONTRATO_MUTUO");

    String uno = render(contrato, datos(false, "Soltera"));
    assertFalse(uno.contains("nodata"));
    assertFalse(uno.contains("V_nombre_conyuge_2"));
    assertTrue(uno.contains("a quien en lo posterior se le denominará LA PARTE DEUDORA"));
    assertEquals(1, ocurrencias(uno, "Nombre: V_nombre_conyuge_1"));

    String dos = render(contrato, datos(true, "Casados"));
    assertTrue(dos.contains("Nombre: V_nombre_conyuge_2"));
    assertTrue(dos.contains("a quien(es) en lo posterior se le(s) denominará"));
  }

  @Test
  void conUnCompradorLosDatosDelSegundoNoQuedanPendientes() {
    MinutaTemplateDescriptor minuta =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, "MINUTA_COMPRAVENTA");
    List<String> pendientes = renderer.camposPendientes(minuta, datos(false, "Soltero"));
    for (String campo : SEGUNDO) {
      assertFalse(pendientes.contains(campo), campo);
    }
    MinutaViviendaData sinSegundo = datos(true, "Casados");
    sinSegundo.setCedulaConyuge2("");
    assertTrue(renderer.camposPendientes(minuta, sinSegundo).contains("cedula_conyuge_2"));
  }

  @Test
  void seccionesSeDerivanDelSegundoCompradorYDelEstadoCivil() {
    assertEquals(
        Map.of(
            MinutaViviendaData.SECCION_TIENE_COMPRADOR_2, false,
            MinutaViviendaData.SECCION_UN_SOLO_COMPRADOR, true,
            MinutaViviendaData.SECCION_COMPRADORES_CASADOS, false),
        datos(false, "Casado").secciones());
    assertTrue(datos(true, "CASADOS").secciones().get(MinutaViviendaData.SECCION_COMPRADORES_CASADOS));
    assertTrue(datos(true, "Unión de hecho").secciones().get(MinutaViviendaData.SECCION_COMPRADORES_CASADOS));
    assertFalse(datos(true, "Solteros").secciones().get(MinutaViviendaData.SECCION_COMPRADORES_CASADOS));
    assertTrue(datos(true, "Solteros").secciones().get(MinutaViviendaData.SECCION_TIENE_COMPRADOR_2));
  }

  @Test
  void laPlantillaDeclaraLasSeccionesYNoLasMuestraComoVariables() {
    MinutaTemplateDescriptor minuta =
        catalog.require(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, "MINUTA_COMPRAVENTA");
    assertTrue(
        renderer.secciones(minuta).containsAll(
            List.of(MinutaViviendaData.SECCION_TIENE_COMPRADOR_2, MinutaViviendaData.SECCION_UN_SOLO_COMPRADOR)));
    assertFalse(renderer.tags(minuta).contains(MinutaViviendaData.SECCION_TIENE_COMPRADOR_2));
    assertTrue(renderer.tags(minuta).contains("nombre_comprador_2"), "los tags de la sección se leen");
  }

  private String render(MinutaTemplateDescriptor descriptor, MinutaViviendaData data) throws Exception {
    byte[] docx = renderer.renderVivienda(descriptor, data);
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      return extractor.getText();
    }
  }

  private static int ocurrencias(String texto, String buscado) {
    return texto.split(java.util.regex.Pattern.quote(buscado), -1).length - 1;
  }

  /** Datos sintéticos completos; sin segundo comprador si {@code dos} es falso. */
  private static MinutaViviendaData datos(boolean dos, String estadoCivil) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : new MinutaViviendaData().toTemplateMap().keySet()) {
      values.put(key, "V_" + key);
    }
    values.put("estado_civil", estadoCivil);
    if (!dos) {
      SEGUNDO.forEach(campo -> values.put(campo, ""));
    }
    return new ObjectMapper().convertValue(values, MinutaViviendaData.class);
  }
}
