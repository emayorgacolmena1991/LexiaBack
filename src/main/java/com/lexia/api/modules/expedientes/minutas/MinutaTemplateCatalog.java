package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Catálogo de plantillas .docx. Hoy: solo {@code VIV_HIPOTECADA_BIESS}. Futuro: más productos aquí.
 */
@Component
public class MinutaTemplateCatalog {

  public static final String PRODUCT_VIV_HIPOTECADA_BIESS = "VIV_HIPOTECADA_BIESS";

  private static final String TEMPLATE_VIV_HIPOTECADA =
      "templates/MINUTA_HIPOTECA_TEMPLATE.docx";

  private static final List<MinutaTemplateDescriptor> TEMPLATES =
      List.of(
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "MINUTA_COMPRAVENTA",
              TEMPLATE_VIV_HIPOTECADA,
              "minuta_compraventa_vivienda_hipotecada.docx"),
          new MinutaTemplateDescriptor(
              PRODUCT_VIV_HIPOTECADA_BIESS,
              "CONTRATO_MUTUO",
              TEMPLATE_VIV_HIPOTECADA,
              "contrato_mutuo_vivienda_hipotecada.docx"));

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
                        + ". Disponible: VIV_HIPOTECADA_BIESS (MINUTA_COMPRAVENTA, CONTRATO_MUTUO)."));
  }

  public boolean supports(String productCode, String templateKind) {
    return find(productCode, templateKind).isPresent();
  }
}
