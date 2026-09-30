package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Catálogo de plantillas .docx por producto BIESS. Hoy: {@code VIV_HIPOTECADA_BIESS},
 * {@code VIV_TERMINADA_PREF} y {@code TERRENO_Y_VIVIENDA}.
 */
@Component
public class MinutaTemplateCatalog {

  public static final String PRODUCT_VIV_HIPOTECADA_BIESS = "VIV_HIPOTECADA_BIESS";
  public static final String PRODUCT_VIV_TERMINADA_PREF = "VIV_TERMINADA_PREF";
  public static final String PRODUCT_TERRENO_Y_VIVIENDA = "TERRENO_Y_VIVIENDA";

  private static final String DIR = "templates/escrituracion/";

  static final String TEMPLATE_MINUTA_HIPOTECA =
      DIR + "SUSTITUCION DE HIPOTECA/minuta_hipoteca.docx";

  static final String TEMPLATE_CONTRATO_MUTUO =
      DIR + "SUSTITUCION DE HIPOTECA/contrato_sustitucion_hipoteca.docx";

  static final String TEMPLATE_PREF_MINUTA_COMPRAVENTA =
      DIR + "VIVIENDA TERMINADA PREFERENCIAL/minuta_compraventa_y_hipoteca_preferencial.docx";

  static final String TEMPLATE_PREF_CONTRATO_MUTUO =
      DIR + "VIVIENDA TERMINADA PREFERENCIAL/contrato_de_vivienda_terminada_y_terreno.docx";

  static final String TEMPLATE_TERRENO_MINUTA_COMPRAVENTA =
      DIR + "TERRENO TERMINADA INDIVIDUAL/minuta_compraventa_y_hipoteca_terreno.docx";

  static final String TEMPLATE_TERRENO_CONTRATO_MUTUO =
      DIR + "TERRENO TERMINADA INDIVIDUAL/contrato_de_vivienda_terminada_y_terreno.docx";

  private static final List<MinutaTemplateDescriptor> TEMPLATES =
      List.of(
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_MINUTA_HIPOTECA,
              "minuta_compraventa_vivienda_hipotecada.docx"),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "CONTRATO_MUTUO",
              TEMPLATE_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_hipotecada.docx"),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_PREF,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_PREF_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_vivienda_preferencial.docx",
              List.of(
                  "nombre_conyuge_1",
                  "cedula_conyuge_1",
                  "nombre_vendedor",
                  "cedula_vendedor",
                  "descripcion_inmueble_hipoteca",
                  "canton_inmueble")),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_TERMINADA_PREF,
              "CONTRATO_MUTUO",
              TEMPLATE_PREF_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_preferencial.docx",
              List.of("nombre_conyuge_1", "cedula_conyuge_1")),
          new MinutaTemplateDescriptor(
              PRODUCT_TERRENO_Y_VIVIENDA,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_TERRENO_MINUTA_COMPRAVENTA,
              "minuta_compraventa_hipoteca_terreno.docx",
              List.of(
                  "nombre_conyuge_1",
                  "cedula_conyuge_1",
                  "nombre_vendedor",
                  "cedula_vendedor",
                  "descripcion_inmueble_hipoteca",
                  "canton_inmueble")),
          new MinutaTemplateDescriptor(
              PRODUCT_TERRENO_Y_VIVIENDA,
              "CONTRATO_MUTUO",
              TEMPLATE_TERRENO_CONTRATO_MUTUO,
              "contrato_mutuo_vivienda_terminada_y_terreno.docx",
              List.of("nombre_conyuge_1", "cedula_conyuge_1")));

  public Optional<MinutaTemplateDescriptor> find(String productCode, String templateKind) {
    if (!StringUtils.hasText(productCode) || !StringUtils.hasText(templateKind)) {
      return Optional.empty();
    }
    String product = productCode.trim().toUpperCase(Locale.ROOT);
    String kind = templateKind.trim().toUpperCase(Locale.ROOT);
    return TEMPLATES.stream()
        .filter(t -> t.productCode().equals(product) && t.templateKind().equals(kind))
        .findFirst();
  }

  public MinutaTemplateDescriptor require(String productCode, String templateKind) {
    return find(productCode, templateKind)
        .orElseThrow(
            () ->
                ApiException.badRequest(
                    "Generación DOCX no implementada aún para producto="
                        + productCode
                        + " plantilla="
                        + templateKind
                        + ". Disponible: VIV_HIPOTECADA_BIESS, VIV_TERMINADA_PREF y"
                        + " TERRENO_Y_VIVIENDA"
                        + " (MINUTA_COMPRAVENTA, CONTRATO_MUTUO)."));
  }

  public boolean supports(String productCode, String templateKind) {
    return find(productCode, templateKind).isPresent();
  }

  public List<MinutaTemplateDescriptor> all() {
    return TEMPLATES;
  }
}
