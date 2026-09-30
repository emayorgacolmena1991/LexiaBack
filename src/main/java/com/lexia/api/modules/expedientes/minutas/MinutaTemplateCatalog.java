package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Catálogo de plantillas .docx por producto BIESS. Generables hoy: {@code SUSTITUCION_HIPOTECA},
 * {@code VIV_TERMINADA_PREF}, {@code TERRENO_Y_VIVIENDA} y {@code VIV_HIPOTECADA_BIESS}. Las
 * plantillas propias de {@code VIV_TERMINADA_IND} y {@code VIV_TERMINADA_SOLID} están registradas
 * pero sin conectar hasta habilitarlas.
 */
@Component
public class MinutaTemplateCatalog {

  public static final String PRODUCT_VIV_HIPOTECADA_BIESS = "VIV_HIPOTECADA_BIESS";
  public static final String PRODUCT_VIV_TERMINADA_PREF = "VIV_TERMINADA_PREF";
  public static final String PRODUCT_TERRENO_Y_VIVIENDA = "TERRENO_Y_VIVIENDA";
  public static final String PRODUCT_SUSTITUCION_HIPOTECA = "SUSTITUCION_HIPOTECA";
  public static final String PRODUCT_VIV_TERMINADA_IND = "VIV_TERMINADA_IND";
  public static final String PRODUCT_VIV_TERMINADA_SOLID = "VIV_TERMINADA_SOLID";

  private static final String DIR = "templates/escrituracion/";

  static final String TEMPLATE_SUST_MINUTA_HIPOTECA =
      DIR + "SUSTITUCION DE HIPOTECA/minuta_hipoteca.docx";

  static final String TEMPLATE_SUST_CONTRATO_MUTUO =
      DIR + "SUSTITUCION DE HIPOTECA/contrato_sustitucion_hipoteca.docx";

  static final String TEMPLATE_HIPOTECADA_MINUTA_COMPRAVENTA =
      DIR + "VIVIENDA HIPOTECADA BIESS/MINUTA_COMPRAVENTA_HIPOTECA.docx";

  static final String TEMPLATE_HIPOTECADA_CONTRATO_MUTUO =
      DIR + "VIVIENDA HIPOTECADA BIESS/CONTRATO_MUTUO_VIVIENDA_HIPOTECADA.docx";

  static final String TEMPLATE_PREF_MINUTA_COMPRAVENTA =
      DIR + "VIVIENDA TERMINADA PREFERENCIAL/minuta_compraventa_y_hipoteca_preferencial.docx";

  static final String TEMPLATE_PREF_CONTRATO_MUTUO =
      DIR + "VIVIENDA TERMINADA PREFERENCIAL/contrato_de_vivienda_terminada_y_terreno.docx";

  static final String TEMPLATE_TERRENO_MINUTA_COMPRAVENTA =
      DIR + "TERRENO TERMINADA INDIVIDUAL/minuta_compraventa_y_hipoteca_terreno.docx";

  static final String TEMPLATE_TERRENO_CONTRATO_MUTUO =
      DIR + "TERRENO TERMINADA INDIVIDUAL/contrato_de_vivienda_terminada_y_terreno.docx";

  static final String TEMPLATE_TERMINADA_MINUTA_COMPRAVENTA =
      DIR
          + "VIVIENDA TERMINADA INDIVIDUAL - SOLIDARIA/"
          + "minuta_vivienda_terminada_compraventa_hipoteca.docx";

  static final String TEMPLATE_TERMINADA_CONTRATO_MUTUO =
      DIR + "VIVIENDA TERMINADA INDIVIDUAL - SOLIDARIA/contrato_vivienda_terminada.docx";

  /**
   * Críticos de generación: solo identidad e inmueble. Monto, tasa, plazo y apoderado se exigen al
   * cierre, no al generar.
   */
  private static final List<String> CRITICOS_MINUTA =
      List.of(
          "nombre_conyuge_1",
          "cedula_conyuge_1",
          "nombre_vendedor",
          "cedula_vendedor",
          "descripcion_inmueble_hipoteca",
          "canton_inmueble");

  private static final List<String> CRITICOS_MINUTA_HIPOTECA =
      List.of(
          "nombre_conyuge_1",
          "cedula_conyuge_1",
          "descripcion_inmueble_hipoteca",
          "canton_inmueble");

  private static final List<String> CRITICOS_CONTRATO =
      List.of("nombre_conyuge_1", "cedula_conyuge_1");

  private static final List<MinutaTemplateDescriptor> TEMPLATES =
      List.of(
          new MinutaTemplateDescriptor(
              PRODUCT_SUSTITUCION_HIPOTECA,
              "MINUTA_HIPOTECA",
              TEMPLATE_SUST_MINUTA_HIPOTECA,
              "minuta_hipoteca_sustitucion.docx",
              CRITICOS_MINUTA_HIPOTECA),
          new MinutaTemplateDescriptor(
              PRODUCT_SUSTITUCION_HIPOTECA,
              "CONTRATO_MUTUO",
              TEMPLATE_SUST_CONTRATO_MUTUO,
              "contrato_mutuo_sustitucion_hipoteca.docx",
              CRITICOS_CONTRATO),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_PREF,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_PREF_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_vivienda_preferencial.docx",
              CRITICOS_MINUTA),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_PREF,
              "CONTRATO_MUTUO",
              TEMPLATE_PREF_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_preferencial.docx",
              CRITICOS_CONTRATO),
          new MinutaTemplateDescriptor(
              PRODUCT_TERRENO_Y_VIVIENDA,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_TERRENO_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_terreno.docx",
              CRITICOS_MINUTA),
          new MinutaTemplateDescriptor(
              PRODUCT_TERRENO_Y_VIVIENDA,
              "CONTRATO_MUTUO",
              TEMPLATE_TERRENO_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_terminada_y_terreno.docx",
              CRITICOS_CONTRATO),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_HIPOTECADA_MINUTA_COMPRAVENTA,
              "minuta_compraventa_vivienda_hipotecada.docx",
              CRITICOS_MINUTA),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "CONTRATO_MUTUO",
              TEMPLATE_HIPOTECADA_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_hipotecada.docx",
              CRITICOS_CONTRATO));

  /**
   * Plantillas propias del producto, registradas pero aún no generables: no se resuelven con {@link
   * #find} ni {@link #require}.
   */
  private static final List<MinutaTemplateDescriptor> SIN_CONECTAR =
      List.of(
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_IND,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_TERMINADA_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_vivienda_terminada.docx",
              CRITICOS_MINUTA),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_IND,
              "CONTRATO_MUTUO",
              TEMPLATE_TERMINADA_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_terminada.docx",
              CRITICOS_CONTRATO),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_SOLID,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_TERMINADA_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_vivienda_solidaria.docx",
              CRITICOS_MINUTA),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_SOLID,
              "CONTRATO_MUTUO",
              TEMPLATE_TERMINADA_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_solidaria.docx",
              CRITICOS_CONTRATO));

  public Optional<MinutaTemplateDescriptor> find(String productCode, String templateKind) {
    return buscar(TEMPLATES, productCode, templateKind);
  }

  public MinutaTemplateDescriptor require(String productCode, String templateKind) {
    return find(productCode, templateKind)
        .orElseThrow(
            () -> {
              if (buscar(SIN_CONECTAR, productCode, templateKind).isPresent()) {
                return ApiException.badRequest(
                    "La plantilla "
                        + templateKind
                        + " de "
                        + productCode
                        + " está registrada pero aún no es generable: tiene campos sin"
                        + " equivalencia en el expediente.");
              }
              return ApiException.badRequest(
                  "Generación DOCX no implementada aún para producto="
                      + productCode
                      + " plantilla="
                      + templateKind
                      + ". Disponible: SUSTITUCION_HIPOTECA (MINUTA_HIPOTECA, CONTRATO_MUTUO),"
                      + " VIV_TERMINADA_PREF, TERRENO_Y_VIVIENDA y VIV_HIPOTECADA_BIESS"
                      + " (MINUTA_COMPRAVENTA, CONTRATO_MUTUO).");
            });
  }

  public boolean supports(String productCode, String templateKind) {
    return find(productCode, templateKind).isPresent();
  }

  public List<MinutaTemplateDescriptor> all() {
    return TEMPLATES;
  }

  /** Plantillas registradas que todavía no se pueden generar. */
  public List<MinutaTemplateDescriptor> sinConectar() {
    return SIN_CONECTAR;
  }

  private static Optional<MinutaTemplateDescriptor> buscar(
      List<MinutaTemplateDescriptor> lista, String productCode, String templateKind) {
    if (!StringUtils.hasText(productCode) || !StringUtils.hasText(templateKind)) {
      return Optional.empty();
    }
    String product = productCode.trim().toUpperCase(Locale.ROOT);
    String kind = templateKind.trim().toUpperCase(Locale.ROOT);
    return lista.stream()
        .filter(t -> t.productCode().equals(product) && t.templateKind().equals(kind))
        .findFirst();
  }
}
