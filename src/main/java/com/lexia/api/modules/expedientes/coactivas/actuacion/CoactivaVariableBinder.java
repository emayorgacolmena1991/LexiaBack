package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fusiona las capas de una actuación. Precedencia: IA &lt; sistema (delegado y liquidación) &lt;
 * overrides. Un tag sin dato queda como el literal {@code nodata}.
 */
public class CoactivaVariableBinder {

  public static final String NODATA = "nodata";
  public static final String IA = "IA";
  public static final String SISTEMA = "SISTEMA";
  public static final String MANUAL = "MANUAL";
  static final int DIAS_LIQUIDACION = 3;

  private static final Locale ES = Locale.forLanguageTag("es-EC");
  private static final DateTimeFormatter FECHA_LARGA =
      DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);
  private static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("d/M/uuuu");
  private static final Pattern LIQUIDACION = Pattern.compile("(?i)liquidacion");
  private static final Pattern TAG_SISTEMA =
      Pattern.compile(
          "(?i)(resolucion_delegacion|nombre_delegado|titulo_delegado|funcionario_coactiva|"
              + "correo_delegado|correo_funcionario|liquidacion|nombre_gerente_general|"
              + "nombre_secretario|nombre_abogado_secretario|abogado_secretario)");

  public record Resultado(
      Map<String, String> variables,
      Map<String, String> datosExtraidos,
      List<String> pendientes,
      Map<String, String> origenes,
      Map<String, String> valoresExtraidos,
      boolean liquidacionVigente) {}

  public static boolean esSistema(String tag) {
    return tag != null && TAG_SISTEMA.matcher(tag).find();
  }

  public Resultado resolver(
      List<String> tags,
      Map<String, String> ia,
      Map<String, String> sistema,
      Map<String, String> overrides,
      LocalDate hoy) {
    Map<String, String> capaIa = ia == null ? Map.of() : ia;
    Map<String, String> capaSistema = sistema == null ? Map.of() : sistema;
    Map<String, String> manual = overrides == null ? Map.of() : overrides;
    boolean vigente = liquidacionVigente(capaSistema, capaIa, hoy);
    if (!vigente) {
      capaIa = sinLiquidacion(capaIa);
      capaSistema = sinLiquidacion(capaSistema);
    }

    Map<String, String> extraidos = new LinkedHashMap<>();
    Map<String, String> variables = new LinkedHashMap<>();
    Map<String, String> origenes = new LinkedHashMap<>();
    Map<String, String> restaurar = new LinkedHashMap<>();
    List<String> pendientes = new ArrayList<>();
    for (String tag : tags) {
      String deIa = limpio(capaIa.get(tag));
      String deSistema = limpio(capaSistema.get(tag));
      String base = deSistema != null ? deSistema : deIa;
      if (base != null) {
        extraidos.put(tag, base);
        restaurar.put(tag, base);
      }
      boolean tieneOverride = manual.containsKey(tag);
      String valor;
      if (tieneOverride) {
        valor = limpio(manual.get(tag));
        origenes.put(tag, MANUAL);
      } else if (deSistema != null) {
        valor = deSistema;
        origenes.put(tag, SISTEMA);
      } else if (deIa != null) {
        valor = deIa;
        origenes.put(tag, IA);
      } else {
        valor = null;
      }
      String visible = valor == null ? NODATA : valor;
      variables.put(tag, visible);
      if (NODATA.equals(visible)) {
        pendientes.add(tag);
      }
    }
    return new Resultado(variables, extraidos, List.copyOf(pendientes), origenes, restaurar, vigente);
  }

  static boolean liquidacionVigente(Map<String, String> sistema, Map<String, String> ia, LocalDate hoy) {
    String fecha =
        primero(
            sistema,
            ia,
            "fecha_liquidacion",
            "fecha_liquidacion_actualizada");
    LocalDate parsed = parseFecha(fecha);
    if (parsed == null || hoy == null) {
      return true;
    }
    return !parsed.isBefore(hoy.minusDays(DIAS_LIQUIDACION));
  }

  static LocalDate parseFecha(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String texto = raw.trim().toLowerCase(ES).replace(" del ", " de ");
    try {
      return LocalDate.parse(texto);
    } catch (DateTimeParseException ignored) {
      // sigue con los formatos del expediente
    }
    try {
      return LocalDate.parse(texto, FECHA_CORTA);
    } catch (DateTimeParseException ignored) {
      // sigue con la fecha larga
    }
    try {
      return LocalDate.parse(texto, FECHA_LARGA);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static Map<String, String> sinLiquidacion(Map<String, String> origen) {
    Map<String, String> out = new LinkedHashMap<>();
    origen.forEach(
        (k, v) -> {
          if (!LIQUIDACION.matcher(k).find()) {
            out.put(k, v);
          }
        });
    return out;
  }

  private static String primero(Map<String, String> sistema, Map<String, String> ia, String... keys) {
    for (String key : keys) {
      String valor = limpio(sistema.get(key));
      if (valor == null) {
        valor = limpio(ia.get(key));
      }
      if (valor != null) {
        return valor;
      }
    }
    return null;
  }

  static String limpio(String valor) {
    if (valor == null) {
      return null;
    }
    String texto = valor.trim();
    if (texto.isEmpty() || "null".equalsIgnoreCase(texto) || NODATA.equalsIgnoreCase(texto)) {
      return null;
    }
    return texto;
  }
}
