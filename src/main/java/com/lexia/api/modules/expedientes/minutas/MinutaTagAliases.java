package com.lexia.api.modules.expedientes.minutas;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Diccionario tag DOCX → campo canónico de {@link MinutaViviendaData}. Las plantillas conservan sus
 * tags originales; aquí solo se declaran nombres distintos para el mismo dato (verificado por el
 * contexto de la cláusula en cada plantilla). Datos conceptualmente distintos (linderos generales o
 * de propiedad horizontal, antecedentes múltiples, celular vs. teléfono cuando la plantilla pide
 * ambos) no se aliasan: siguen siendo tags sin resolver hasta que exista su campo canónico.
 */
public final class MinutaTagAliases {

  private record Alias(String campo, UnaryOperator<String> formato) {}

  private static final Map<String, Alias> ALIASES = new LinkedHashMap<>();

  static {
    // Parte compradora / deudora. Las plantillas solidarias los declaran "los cónyuges", igual que
    // nombre_conyuge_1/2 en las preferenciales.
    alias(
        "nombre_conyuge_1",
        "nombre_comprador",
        "nombre_comprador_1",
        "nombre_deudor",
        "nombre_deudor_1");
    alias("cedula_conyuge_1", "cedula_comprador_1", "cedula_deudor_1");
    alias(
        "profesion_conyuge_1",
        "profesion_comprador_1",
        "profesion_deudor",
        "profesion_deudor_1",
        "profesion_deudores");
    alias("representacion_sociedad_conyugal_comprador", "representacion_sociedad_conyugal_deudor");
    alias("nombre_conyuge_2", "nombre_comprador_2", "nombre_deudor_2");
    alias("cedula_conyuge_2", "cedula_comprador_2", "cedula_deudor_2");
    alias("profesion_conyuge_2", "profesion_comprador_2", "profesion_deudor_2");
    alias(
        "estado_civil",
        "estado_civil_comprador",
        "estado_civil_compradores",
        "estado_civil_deudor",
        "estado_civil_deudores");
    alias(
        "direccion_deudor",
        "direccion_comprador",
        "direccion_compradores",
        "direccion_deudores",
        "domicilio_deudor");
    // celular_* en plural ocupa el mismo lugar que telefono_* en las preferenciales ("número de
    // celular"); celular_comprador/celular_vendedor (singular) conviven con telefono_* y no se aliasan.
    alias("telefono_deudor", "telefono_comprador", "telefono_deudores", "celular_compradores");
    alias("correo_deudor", "correo_comprador", "correo_deudores", "email_compradores");
    alias(
        "canton_domicilio",
        "canton_domicilio_deudores",
        "ciudad_comparecientes",
        "ciudad_domicilio_hipoteca");
    alias("nombre_afiliado", "nombre_afiliados_jubilados", "nombres_deudores_completo");

    // Parte vendedora.
    alias("nombre_vendedor", "nombre_vendedor_1", "nombre_vendedores");
    alias("cedula_vendedor", "cedula_vendedor_1");
    alias("nombre_conyuge_vendedor", "nombre_vendedor_2");
    alias("cedula_conyuge_vendedor", "cedula_vendedor_2");
    alias("profesion_vendedor", "profesion_vendedor_1");
    alias("estado_civil_vendedor", "estado_civil_vendedores");
    alias("direccion_vendedor", "direccion_vendedores");
    alias("telefono_vendedor", "celular_vendedores");
    alias("correo_vendedor", "email_vendedores");

    // Inmueble (mismo bien en la compraventa y en la hipoteca).
    alias("clave_catastral", "codigo_catastral");
    alias("alicuota", "alicuota_inmueble");
    alias("superficie_m2", "area_total_inmueble", "superficie_m2_hipoteca");
    alias("lindero_norte", "lindero_hipoteca_norte");
    alias("lindero_sur", "lindero_hipoteca_sur");
    alias("lindero_este", "lindero_hipoteca_este");
    alias("lindero_oeste", "lindero_hipoteca_oeste");
    alias("parroquia_inmueble", "parroquia_hipoteca");
    alias("provincia_inmueble", "provincia_hipoteca");
    // El Registro de la Propiedad y el GAD municipal son los del cantón donde está el inmueble.
    alias(
        "canton_inmueble",
        "canton_hipoteca",
        "canton_gad_costos",
        "canton_municipio_tributos",
        "canton_registro_antecedente",
        "canton_registro_adquisicion");
    alias(
        "descripcion_inmuebles_antecedentes",
        "descripcion_inmueble",
        "descripcion_detalle_inmueble_hipoteca",
        "descripcion_inmuebles_hipotecados");
    alias("descripcion_inmueble_hipoteca", "descripcion_inmueble_hipotecado");

    // Antecedente de dominio del vendedor.
    alias("fecha_escritura_antecedente", "fecha_escritura_adquisicion");
    alias("fecha_inscripcion_antecedente", "fecha_inscripcion_adquisicion");
    alias("notaria_antecedente", "notario_adquisicion");

    // Precio y crédito.
    alias("precio_compraventa_numero", "precio_venta_numeral", "precio_num");
    alias("precio_compraventa_letras", "precio_venta_literal", "precio_letras");
    alias("valor_entrada_numero", "cuota_inicial_num");
    alias("valor_entrada_letras", "cuota_inicial_letras");
    alias("saldo_compraventa_numero", "saldo_credito_num");
    alias("saldo_compraventa_letras", "saldo_credito_letras");
    alias("apoderado_biess", "nombre_representante_biess");
    alias("monto_prestamo", "monto_prestamo_numero", "monto_prestamo_numeral");
    alias("monto_prestamo_letras", "monto_prestamo_literal");
    // La plantilla ya escribe "{{plazo_meses}} meses".
    alias("plazo_credito", v -> v.replaceFirst("(?i)\\s*meses$", ""), "plazo_meses");
    alias("institucion_financiera_original", "banco_hipoteca_anterior");
  }

  private MinutaTagAliases() {}

  private static void alias(String campo, String... tags) {
    alias(campo, UnaryOperator.identity(), tags);
  }

  private static void alias(String campo, UnaryOperator<String> formato, String... tags) {
    for (String tag : tags) {
      if (ALIASES.put(tag, new Alias(campo, formato)) != null) {
        throw new IllegalStateException("Alias duplicado: " + tag);
      }
    }
  }

  /** Campo canónico del tag; el propio tag si no es un alias. */
  public static String canonico(String tag) {
    Alias alias = ALIASES.get(tag);
    return alias == null ? tag : alias.campo();
  }

  /** Alias declarados (tag → campo canónico). */
  static Map<String, String> aliases() {
    Map<String, String> out = new LinkedHashMap<>();
    ALIASES.forEach((tag, alias) -> out.put(tag, alias.campo()));
    return Collections.unmodifiableMap(out);
  }

  /** Tags que no son campo canónico ni alias de uno. */
  static List<String> sinResolver(Collection<String> tags, Set<String> campos) {
    return tags.stream().filter(tag -> !campos.contains(canonico(tag))).toList();
  }

  /**
   * Valores canónicos más una entrada por cada tag alias de la plantilla, con el valor de su campo
   * canónico (ya normalizado por {@link MinutaViviendaData#toTemplateMap()}).
   */
  static Map<String, Object> valoresPorTag(Collection<String> tags, Map<String, Object> campos) {
    Map<String, Object> out = new LinkedHashMap<>(campos);
    for (String tag : tags) {
      Alias alias = ALIASES.get(tag);
      if (alias == null || !campos.containsKey(alias.campo())) {
        continue;
      }
      Object valor = campos.get(alias.campo());
      out.put(
          tag,
          MinutaViviendaData.isMissing(valor) ? valor : alias.formato().apply(valor.toString()));
    }
    return out;
  }
}
