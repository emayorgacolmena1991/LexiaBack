package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ProvidenciaEmbargoPlantillaTest {

  private static final String RUTA = "templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx";

  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();

  @Test
  void elMapperResuelveTodasLasVariablesDeLaPlantilla() {
    List<String> tags = renderer.tags(RUTA);
    assertTrue(tags.contains("numero_juicio_coactivo"));
    assertTrue(tags.contains("monto_honorarios_letras"));
    assertTrue(tags.contains("correo_notificacion_deudor"), "tags dentro de hipervínculos");
    assertTrue(tags.contains("correo_estudio_juridico_externo"));
    assertFalse(tags.stream().anyMatch(t -> t.toUpperCase().contains("BANECUADOR")));

    PlantillaDatos datos = datosCompletos();
    assertEquals(List.of(), datos.faltantes(tags));
  }

  @Test
  void generaDocxYPdfSinPlaceholders() throws Exception {
    PlantillaDatos datos = datosCompletos();
    byte[] docx = renderer.renderPlantilla(RUTA, datos.valores());

    String texto;
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      texto = extractor.getText();
    }
    assertFalse(texto.contains("{{"));
    assertFalse(texto.contains("[pendiente]"));
    assertTrue(texto.contains("BANECUADOR B.P."));
    assertTrue(texto.contains("DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA"));
    assertTrue(texto.contains("el/la señor(a) JUAN CARLOS PEREZ LOPEZ"));
    assertTrue(texto.contains("USD $1,250.50 (MIL DOSCIENTOS CINCUENTA CON 50/100"));
    assertTrue(texto.contains("juan.perez@mail.com"));
    assertTrue(texto.contains("estudio@juridico.ec"));

    byte[] pdf = new DocxPdfConverter("", "target/coactivas-test").toPdf(docx);
    assertTrue(pdf.length > 1000);
    assertEquals("%PDF", new String(pdf, 0, 4));
  }

  @Test
  void sinDatosObligatoriosElRendererNoGenera() {
    Map<String, Object> incompletos = new HashMap<>(datosCompletos().valores());
    incompletos.remove("nombre_garante_solidario");
    assertThrows(ApiException.class, () -> renderer.renderPlantilla(RUTA, incompletos));
  }

  @Test
  void errorDeDatosIncompletosListaLasVariables() {
    ApiException e =
        CoactivaActuacionService.datosIncompletos(List.of("nombre_garante_solidario", "numero_cuenta_1"), true);
    assertEquals(CoactivaActuacionService.PLANTILLA_DATOS_INCOMPLETOS, e.getCode());
    assertEquals(422, e.getStatus().value());
    assertEquals(List.of("nombre_garante_solidario", "numero_cuenta_1"), e.getDetails().get("variablesFaltantes"));
    assertTrue(e.getMessage().contains("faltan 2 datos obligatorios"));
  }

  private static PlantillaDatos datosCompletos() {
    return CoactivaPlantillaDataMapper.construir(
        CoactivaPlantillaDataMapperTest.fuentes(CoactivaPlantillaDataMapperTest.ocrCompleto()),
        CoactivaPlantillaDataMapperTest.AHORA);
  }
}
