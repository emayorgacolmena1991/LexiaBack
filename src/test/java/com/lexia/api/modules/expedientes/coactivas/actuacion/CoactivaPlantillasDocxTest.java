package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class CoactivaPlantillasDocxTest {

  private static final List<String> RUTAS =
      List.of(
          "templates/coactivas/ACTA_ENTREGA_COPIAS_CERTIFICADAS.docx",
          "templates/coactivas/ACTA_POSESION_DEPOSITARIO_JUDICIAL_EMBARGO_CHEQUE.docx",
          "templates/coactivas/CORRER_TRASLADO_FACILIDAD_PAGO.docx",
          "templates/coactivas/EMBARGO_TRANSFERENCIA.docx",
          "templates/coactivas/EMBARGO_VALORES_TRANSFERENCIA.docx",
          "templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_EMBARGO_TEMPLATE.docx",
          "templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_POR_ABONO_TEMPLATE.docx",
          "templates/coactivas/FORMATO_PROV_CONTESTACION_ESCRITO_TEMPLATE.docx",
          "templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx",
          "templates/coactivas/IMPULSO_ORDEN_PAGO_VARIABLES.docx",
          "templates/coactivas/INFORME_HONORARIOS_ABONO_VARIABLES.docx",
          "templates/coactivas/INFORME_HONORARIOS_EMBARGO_VARIABLES_ANEXOS.docx",
          "templates/coactivas/OFICIO_EMBARGO_VARIABLES.docx",
          "templates/coactivas/RATIFICACION_CHONE_VARIABLES.docx",
          "templates/coactivas/RATIFICACION_GYE_VARIABLES.docx",
          "templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES.docx",
          "templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES_2.docx",
          "templates/coactivas/SOLICITUD_CARGA_HONORARIOS_EMBARGO_TRANSFERENCIA.docx");

  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();

  @Test
  void cadaPlantillaRenderizaSinPlaceholders() throws Exception {
    for (String ruta : RUTAS) {
      List<String> tags = renderer.tags(ruta);
      assertFalse(tags.isEmpty(), ruta);
      Map<String, Object> data = new HashMap<>();
      tags.forEach(tag -> data.put(tag, "[COMPLETAR: " + tag + "]"));
      byte[] docx = renderer.renderPlantilla(ruta, data);
      String texto;
      try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
          XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
        texto = extractor.getText();
      }
      assertFalse(texto.contains("{{"), ruta);
      assertTrue(texto.contains("[COMPLETAR:"), ruta);
    }
  }
}
