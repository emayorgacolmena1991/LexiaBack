package com.lexia.api.modules.ia.prompt;

import com.lexia.api.modules.expedientes.minutas.MinutaTagAliases;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateCatalog;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Variables a extraer por acto (producto + plantilla). El texto vive en {@code lexia.prompts};
 * aquí solo el esquema JSON. TICKET-AI-104.
 */
@Component
public class ActoVariableCatalog {

  public static final String PROMPT_EXTRACCION_ACTO = "PROMPT_EXTRACCION_ACTO";
  public static final String PROMPT_BIESS_VISION = "PROMPT_BIESS_VISION";

  private static final List<String> DEFAULT_CAMPOS =
      List.of(
          "nombre_conyuge_1",
          "cedula_conyuge_1",
          "nombre_conyuge_2",
          "cedula_conyuge_2",
          "profesion_conyuge_1",
          "profesion_conyuge_2",
          "canton_domicilio",
          "nombre_afiliado",
          "descripcion_inmuebles_antecedentes",
          "descripcion_inmueble_hipoteca",
          "parroquia_inmueble",
          "canton_inmueble",
          "provincia_inmueble",
          "lindero_norte",
          "lindero_sur",
          "lindero_este",
          "lindero_oeste",
          "superficie_m2",
          "area_solar",
          "area_construccion",
          "area_util",
          "area_comun",
          "alicuota",
          "estado_civil",
          "monto_prestamo",
          "monto_prestamo_letras",
          "plazo_credito",
          "tasa_interes_inicial",
          "institucion_financiera_original",
          "direccion_deudor",
          "telefono_deudor",
          "correo_deudor",
          "ciudad_firma",
          "fecha_firma",
          "apoderado_biess",
          "cedula_apoderado_biess",
          "nombre_vendedor",
          "cedula_vendedor",
          "estado_civil_vendedor",
          "nombre_conyuge_vendedor",
          "cedula_conyuge_vendedor",
          "profesion_vendedor",
          "direccion_vendedor",
          "telefono_vendedor",
          "correo_vendedor",
          "clave_catastral",
          "avaluo_inmueble",
          "precio_compraventa_numero",
          "precio_compraventa_letras",
          "valor_entrada_numero",
          "valor_entrada_letras",
          "saldo_compraventa_numero",
          "saldo_compraventa_letras",
          "fecha_escritura_antecedente",
          "fecha_inscripcion_antecedente",
          "repertorio_antecedente",
          "notaria_antecedente");

  /** Minuta vivienda terminada (individual / solidaria). instrucciones/variables.md. */
  private static final List<String> MINUTA_VIVIENDA_TERMINADA =
      campos(
          "nombre_vendedor_1",
          "cedula_vendedor_1",
          "profesion_vendedor_1",
          "nombre_vendedor_2",
          "cedula_vendedor_2",
          "estado_civil_vendedores",
          "direccion_vendedores",
          "celular_vendedores",
          "email_vendedores",
          "nombre_comprador_1",
          "cedula_comprador_1",
          "profesion_comprador_1",
          "nombre_comprador_2",
          "cedula_comprador_2",
          "profesion_comprador_2",
          "estado_civil_compradores",
          "direccion_compradores",
          "celular_compradores",
          "email_compradores",
          "ciudad_comparecientes",
          "descripcion_inmueble",
          "lindero_norte",
          "lindero_sur",
          "lindero_este",
          "lindero_oeste",
          "area_util",
          "area_comun",
          "area_total_inmueble",
          "area_construccion",
          "area_solar",
          "alicuota_inmueble",
          "parroquia_inmueble",
          "canton_inmueble",
          "provincia_inmueble",
          "codigo_catastral",
          "fecha_escritura_antecedente",
          "fecha_inscripcion_antecedente",
          "precio_letras",
          "precio_num",
          "cuota_inicial_letras",
          "cuota_inicial_num",
          "saldo_credito_letras",
          "saldo_credito_num",
          "nombre_afiliados_jubilados",
          "descripcion_inmueble_hipotecado",
          "parroquia_hipoteca",
          "canton_hipoteca",
          "provincia_hipoteca",
          "lindero_hipoteca_norte",
          "superficie_m2_hipoteca");

  /** Contrato vivienda terminada individual / solidaria. */
  private static final List<String> CONTRATO_VIVIENDA_TERMINADA =
      campos(
          "nombre_deudor_1",
          "cedula_deudor_1",
          "nombre_deudor_2",
          "cedula_deudor_2",
          "estado_civil_deudores",
          "profesion_deudor_1",
          "profesion_deudor_2",
          "canton_domicilio",
          "nombres_deudores_completo",
          "monto_prestamo_numeral",
          "monto_prestamo_literal",
          "plazo_meses",
          "tasa_interes_inicial",
          "nombre_vendedor",
          "direccion_deudores",
          "telefono_deudores",
          "correo_deudores",
          "ciudad_firma",
          "fecha_firma");

  /** Contrato preferencial y terreno (21 variables). */
  private static final List<String> CONTRATO_PREFERENCIAL_TERRENO =
      campos(
          "apoderado_biess",
          "cedula_apoderado_biess",
          "nombre_conyuge_1",
          "cedula_conyuge_1",
          "profesion_conyuge_1",
          "nombre_conyuge_2",
          "cedula_conyuge_2",
          "profesion_conyuge_2",
          "estado_civil",
          "canton_domicilio",
          "nombre_afiliado",
          "monto_prestamo",
          "monto_prestamo_letras",
          "plazo_credito",
          "tasa_interes_inicial",
          "nombre_vendedor",
          "direccion_deudor",
          "telefono_deudor",
          "correo_deudor",
          "ciudad_firma",
          "fecha_firma");

  /** Minuta terreno (49). */
  private static final List<String> MINUTA_TERRENO =
      campos(
          "nombre_vendedor",
          "cedula_vendedor",
          "estado_civil_vendedor",
          "profesion_vendedor",
          "nombre_conyuge_vendedor",
          "cedula_conyuge_vendedor",
          "direccion_vendedor",
          "telefono_vendedor",
          "correo_vendedor",
          "nombre_conyuge_1",
          "cedula_conyuge_1",
          "profesion_conyuge_1",
          "nombre_conyuge_2",
          "cedula_conyuge_2",
          "profesion_conyuge_2",
          "estado_civil",
          "canton_domicilio",
          "nombre_afiliado",
          "direccion_deudor",
          "telefono_deudor",
          "correo_deudor",
          "descripcion_inmuebles_antecedentes",
          "descripcion_inmueble_hipoteca",
          "lindero_norte",
          "lindero_sur",
          "lindero_este",
          "lindero_oeste",
          "superficie_m2",
          "area_util",
          "area_comun",
          "area_construccion",
          "area_solar",
          "alicuota",
          "parroquia_inmueble",
          "canton_inmueble",
          "provincia_inmueble",
          "clave_catastral",
          "fecha_escritura_antecedente",
          "notaria_antecedente",
          "fecha_inscripcion_antecedente",
          "repertorio_antecedente",
          "precio_compraventa_numero",
          "precio_compraventa_letras",
          "valor_entrada_numero",
          "valor_entrada_letras",
          "saldo_compraventa_numero",
          "saldo_compraventa_letras",
          "apoderado_biess",
          "cedula_apoderado_biess");

  /** Minuta preferencial: contrato está en variables.md; la minuta es el subconjunto de terreno. */
  private static final List<String> MINUTA_PREFERENCIAL =
      campos(
          "apoderado_biess",
          "canton_domicilio",
          "canton_inmueble",
          "cedula_apoderado_biess",
          "cedula_conyuge_1",
          "cedula_conyuge_2",
          "cedula_conyuge_vendedor",
          "cedula_vendedor",
          "clave_catastral",
          "correo_deudor",
          "correo_vendedor",
          "descripcion_inmueble_hipoteca",
          "descripcion_inmuebles_antecedentes",
          "direccion_deudor",
          "direccion_vendedor",
          "estado_civil_vendedor",
          "estado_civil",
          "fecha_escritura_antecedente",
          "fecha_inscripcion_antecedente",
          "lindero_este",
          "lindero_norte",
          "lindero_oeste",
          "lindero_sur",
          "nombre_afiliado",
          "nombre_conyuge_1",
          "nombre_conyuge_2",
          "nombre_conyuge_vendedor",
          "nombre_vendedor",
          "notaria_antecedente",
          "parroquia_inmueble",
          "precio_compraventa_letras",
          "precio_compraventa_numero",
          "profesion_conyuge_1",
          "profesion_conyuge_2",
          "profesion_vendedor",
          "provincia_inmueble",
          "repertorio_antecedente",
          "saldo_compraventa_letras",
          "saldo_compraventa_numero",
          "superficie_m2",
          "telefono_deudor",
          "telefono_vendedor",
          "valor_entrada_letras",
          "valor_entrada_numero");

  /** Minuta vivienda hipotecada BIESS. */
  private static final List<String> MINUTA_HIPOTECADA =
      campos(
          "nombre_vendedor",
          "estado_civil_vendedor",
          "representacion_sociedad_conyugal_vendedor",
          "direccion_vendedor",
          "telefono_vendedor",
          "celular_vendedor",
          "correo_vendedor",
          "nombre_comprador",
          "estado_civil_comprador",
          "profesion_deudor",
          "representacion_sociedad_conyugal_comprador",
          "direccion_comprador",
          "telefono_comprador",
          "celular_comprador",
          "correo_comprador",
          "ciudad_comparecientes",
          "fecha_escritura_adquisicion",
          "notario_adquisicion",
          "canton_notario_adquisicion",
          "canton_registro_adquisicion",
          "fecha_inscripcion_adquisicion",
          "descripcion_inmueble_general",
          "nombre_conjunto_edificio",
          "fecha_escritura_ph",
          "notario_ph",
          "canton_notario_ph",
          "canton_registro_ph",
          "fecha_inscripcion_ph",
          "fecha_escritura_antecedente_tres",
          "notario_antecedente_tres",
          "canton_notario_antecedente_tres",
          "canton_registro_antecedente_tres",
          "fecha_inscripcion_antecedente_tres",
          "nombre_adquirente_anterior",
          "estado_civil_adquirente_anterior",
          "linderos_generales_norte",
          "linderos_generales_sur",
          "linderos_generales_este",
          "linderos_generales_oeste",
          "superficie_general",
          "detalles_linderos_especificos_completos",
          "descripcion_inmuebles_hipotecados",
          "modo_propiedad_horizontal",
          "precio_venta_numeral",
          "precio_venta_literal",
          "detalle_forma_pago",
          "canton_municipio_tributos");

  /** Contrato mutuo vivienda hipotecada BIESS. */
  private static final List<String> CONTRATO_HIPOTECADA =
      campos(
          "nombre_representante_biess",
          "nombre_deudor_1",
          "cedula_deudor_1",
          "art_y_deudor_2",
          "nombre_deudor_2",
          "cedula_deudor_2",
          "estado_civil_deudores",
          "profesion_deudores",
          "domicilio_deudor",
          "nombre_afiliado",
          "calidad_afiliado",
          "monto_prestamo_numero",
          "monto_prestamo_letras",
          "plazo_meses",
          "tasa_interes_inicial",
          "banco_hipoteca_anterior",
          "nombre_vendedores",
          "telefono_deudor",
          "correo_deudor",
          "ciudad_firma");

  private static final List<String> MINUTA_SUSTITUCION =
      campos(
          "canton_domicilio",
          "canton_inmueble",
          "cedula_conyuge_1",
          "cedula_conyuge_2",
          "descripcion_inmueble_hipoteca",
          "descripcion_inmuebles_antecedentes",
          "lindero_este",
          "lindero_norte",
          "lindero_oeste",
          "lindero_sur",
          "nombre_afiliado",
          "nombre_conyuge_1",
          "nombre_conyuge_2",
          "parroquia_inmueble",
          "profesion_conyuge_1",
          "profesion_conyuge_2",
          "provincia_inmueble",
          "superficie_m2");

  private static final List<String> CONTRATO_SUSTITUCION =
      campos(
          "cedula_conyuge_1",
          "cedula_conyuge_2",
          "ciudad_firma",
          "correo_deudor",
          "direccion_deudor",
          "estado_civil",
          "fecha_firma",
          "institucion_financiera_original",
          "monto_prestamo",
          "monto_prestamo_letras",
          "nombre_afiliado",
          "nombre_conyuge_1",
          "nombre_conyuge_2",
          "plazo_credito",
          "profesion_conyuge_1",
          "tasa_interes_inicial",
          "telefono_deudor");

  private static final Map<String, List<String>> POR_ACTO =
      Map.ofEntries(
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, "MINUTA_COMPRAVENTA"), MINUTA_VIVIENDA_TERMINADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_IND, "CONTRATO_MUTUO"), CONTRATO_VIVIENDA_TERMINADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID, "MINUTA_COMPRAVENTA"), MINUTA_VIVIENDA_TERMINADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_SOLID, "CONTRATO_MUTUO"), CONTRATO_VIVIENDA_TERMINADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, "MINUTA_COMPRAVENTA"), MINUTA_PREFERENCIAL),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_TERMINADA_PREF, "CONTRATO_MUTUO"), CONTRATO_PREFERENCIAL_TERRENO),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_TERRENO_Y_VIVIENDA, "MINUTA_COMPRAVENTA"), MINUTA_TERRENO),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_TERRENO_Y_VIVIENDA, "CONTRATO_MUTUO"), CONTRATO_PREFERENCIAL_TERRENO),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, "MINUTA_COMPRAVENTA"), MINUTA_HIPOTECADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, "CONTRATO_MUTUO"), CONTRATO_HIPOTECADA),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "MINUTA_HIPOTECA"), MINUTA_SUSTITUCION),
          Map.entry(key(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "CONTRATO_MUTUO"), CONTRATO_SUSTITUCION));

  private final PromptRegistryService prompts;

  public ActoVariableCatalog(PromptRegistryService prompts) {
    this.prompts = prompts;
  }

  public ActoVariableSchema resolve(String productCode, String templateKind) {
    String product = norm(productCode);
    String kind = norm(templateKind);
    List<String> campos = POR_ACTO.get(key(product, kind));
    if (campos == null) {
      return new ActoVariableSchema("DEFAULT", product, kind, DEFAULT_CAMPOS);
    }
    return new ActoVariableSchema(product + ":" + kind, product, kind, campos);
  }

  public String systemPrompt(String productCode, String templateKind) {
    ActoVariableSchema schema = resolve(productCode, templateKind);
    String base =
        prompts.resolvePrompt(PROMPT_EXTRACCION_ACTO, Map.of("tipoActo", schema.codigo()));
    StringBuilder keys = new StringBuilder();
    for (String campo : schema.campos()) {
      keys.append("- ").append(campo).append('\n');
    }
    return base
        + "\nClaves obligatorias dentro de \"variables\" (string o null). No agregues otras:\n"
        + keys;
  }

  public String visionPrompt() {
    return prompts.getRawPrompt(PROMPT_BIESS_VISION);
  }

  public Map<String, List<String>> esquemas() {
    return POR_ACTO;
  }

  private static List<String> campos(String... tags) {
    Set<String> known = new MinutaViviendaData().toTemplateMap().keySet();
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String tag : tags) {
      String campo = MinutaTagAliases.canonico(tag);
      if (known.contains(campo)) {
        out.add(campo);
      }
    }
    return List.copyOf(out);
  }

  private static String key(String product, String kind) {
    return norm(product) + "|" + norm(kind);
  }

  private static String norm(String value) {
    return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
  }

  public record ActoVariableSchema(
      String codigo, String productCode, String templateKind, List<String> campos) {

    public ActoVariableSchema {
      campos = campos == null ? List.of() : List.copyOf(campos);
    }
  }
}
