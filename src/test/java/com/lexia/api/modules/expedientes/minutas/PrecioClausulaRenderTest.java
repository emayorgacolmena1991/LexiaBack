package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Forma;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Pago;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** Cláusula de precio renderizada con el cálculo real (no con marcadores) en cada producto. */
class PrecioClausulaRenderTest {

  private static final String QUINIENTOS =
      "USD 500,00 (QUINIENTOS CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)";
  private static final String SALDO_BIESS =
      "que corresponde al saldo del precio, con el préstamo hipotecario que el Banco del Instituto"
          + " Ecuatoriano de Seguridad Social, BIESS, otorga a LA PARTE COMPRADORA";

  private final MinutaTemplateCatalog catalog = new MinutaTemplateCatalog();
  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();

  @Test
  void hipotecadaRedactaAnticiposYSaldoBiess() throws Exception {
    String texto = render(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, unAnticipo());

    assertFalse(texto.contains("nodata"));
    assertTrue(
        texto.contains(
            "la suma de USD 80.500,00 (OCHENTA MIL QUINIENTOS CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS"
                + " DE AMÉRICA), que serán cancelados de la siguiente manera: a) "
                + QUINIENTOS
                + " mediante transferencia bancaria realizada el 12/10/2026; y, b) USD 80.000,00 (OCHENTA"
                + " MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA) "
                + SALDO_BIESS
                + "."),
        texto);
  }

  @Test
  void individualPreferencialYTerrenoSoloLlevanAnticiposTrasLaEntrada() throws Exception {
    for (String producto :
        List.of(
            MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND,
            MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF,
            MinutaTemplateCatalog.PRODUCT_TERRENO_Y_VIVIENDA)) {
      String texto = render(producto, unAnticipo());

      assertFalse(texto.contains("nodata"), producto);
      assertTrue(
          texto.contains(
              "QUINIENTOS CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA  (USD 500,00), pagados"
                  + " mediante transferencia bancaria realizada el 12/10/2026, y el valor restante de"),
          producto);
      assertTrue(texto.contains("(USD 80.000,00), que LA PARTE COMPRADORA paga"), producto);
      // El saldo lo redacta la plantilla: el texto del BIESS de Hipotecada no se repite.
      assertFalse(texto.contains(SALDO_BIESS), producto);
    }
  }

  @Test
  void variosAnticiposTrasLaEntradaVanComoLiterales() throws Exception {
    PrecioFormaPago.Calculado calculado =
        PrecioFormaPago.calcular(
            new BigDecimal("80500"),
            List.of(
                new Pago(Forma.EFECTIVO, new BigDecimal("200"), LocalDate.of(2026, 9, 1), ""),
                new Pago(Forma.TRANSFERENCIA, new BigDecimal("300"), LocalDate.of(2026, 10, 12), "")),
            null);
    String texto = render(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, calculado);

    assertTrue(
        texto.contains(
            "(USD 500,00), pagados de la siguiente manera: a) USD 200,00 (DOSCIENTOS CON 00/100 DÓLARES"
                + " DE LOS ESTADOS UNIDOS DE AMÉRICA) en dinero en efectivo entregado el 01/09/2026; y, b)"
                + " USD 300,00 (TRESCIENTOS CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA) mediante"
                + " transferencia bancaria realizada el 12/10/2026, y el valor restante de"),
        texto);
  }

  private static PrecioFormaPago.Calculado unAnticipo() {
    return PrecioFormaPago.calcular(
        new BigDecimal("80500"),
        List.of(new Pago(Forma.TRANSFERENCIA, new BigDecimal("500"), LocalDate.of(2026, 10, 12), "")),
        new BigDecimal("80000"));
  }

  private String render(String producto, PrecioFormaPago.Calculado calculado) throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : new MinutaViviendaData().toTemplateMap().keySet()) {
      values.put(key, "V_" + key);
    }
    values.putAll(calculado.capa());
    // Las letras salen de la cifra (MinutaViviendaData.letras); la extraída no se usa.
    MinutaViviendaData data = new ObjectMapper().convertValue(values, MinutaViviendaData.class);
    byte[] docx = renderer.renderVivienda(catalog.require(producto, "MINUTA_COMPRAVENTA"), data);
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      return extractor.getText();
    }
  }
}
