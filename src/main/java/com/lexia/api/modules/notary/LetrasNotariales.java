package com.lexia.api.modules.notary;

import com.lexia.api.modules.expedientes.minutas.NumeroALetras;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cifras de la escritura notarial también en letras, con el formato de la minuta del bufete:
 * {@code número (letras)}. Cédulas, teléfonos y montos usan el mismo conversor que el bufete
 * ({@link NumeroALetras#digitosALetras}, {@link NumeroALetras#montoBancario}); superficies, fechas
 * y clave catastral, que el bufete no convierte, usan la lógica de esta clase. Cada tag numérico
 * {@code x} de la plantilla tiene su derivado {@code x_completo}.
 */
final class LetrasNotariales {

  enum Tipo {
    DIGITOS,
    MONTO,
    SUPERFICIE,
    CLAVE_CATASTRAL,
    FECHA,
    FECHA_OTORGAMIENTO
  }

  /**
   * Derivados de una plantilla: {@code completos} por tag {@code x_completo}; {@code sinConvertir}
   * = tags de origen con valor que no es cifra ni fecha interpretable y tampoco trae letras.
   */
  record Resultado(Map<String, String> completos, List<String> sinConvertir) {}

  static final String SUFIJO = "_completo";
  static final String DOLARES = "DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA";

  /** Tag numérico de la plantilla notarial → conversión de su {@code _completo}. */
  static final Map<String, Tipo> CONVERSIONES = new LinkedHashMap<>();

  /** Montos: la clave {@code _letras} que ya trae el payload. */
  static final Map<String, String> LETRAS_MONTO =
      Map.of(
          "precio_compraventa_numero", "precio_compraventa_letras",
          "valor_entrada_numero", "valor_entrada_letras",
          "monto_prestamo", "monto_prestamo_letras");

  static {
    for (String tag :
        List.of(
            "cedula_conyuge_1",
            "cedula_conyuge_2",
            "cedula_apoderado_biess",
            "cedula_representante_vendedor",
            "ruc_vendedor",
            "telefono_vendedor",
            "telefono_deudor")) {
      CONVERSIONES.put(tag, Tipo.DIGITOS);
    }
    LETRAS_MONTO.keySet().stream().sorted().forEach(tag -> CONVERSIONES.put(tag, Tipo.MONTO));
    CONVERSIONES.put("superficie_m2", Tipo.SUPERFICIE);
    CONVERSIONES.put("superficie_general", Tipo.SUPERFICIE);
    CONVERSIONES.put("area_construccion", Tipo.SUPERFICIE);
    CONVERSIONES.put("clave_catastral", Tipo.CLAVE_CATASTRAL);
    CONVERSIONES.put("fecha_escritura_antecedente", Tipo.FECHA);
    CONVERSIONES.put("fecha_inscripcion_antecedente", Tipo.FECHA);
    CONVERSIONES.put("fecha_otorgamiento", Tipo.FECHA_OTORGAMIENTO);
  }

  private static final Locale ES = Locale.forLanguageTag("es");
  private static final String[] MESES = {
    "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
    "octubre", "noviembre", "diciembre"
  };
  private static final Pattern LETRA = Pattern.compile("\\p{L}");
  private static final Pattern NUMERO_DIGITOS =
      Pattern.compile("\\+?\\d[\\d ]*(?:\\s*[-–]\\s*\\d[\\d ]*)*");
  /** Formato de {@code MinutaViviendaData.digitosConLetras}: «número (LETRAS EN MAYÚSCULAS)». */
  private static final Pattern CON_LETRAS_DEL_BUFETE =
      Pattern.compile("(.*\\d.*?) \\(([A-ZÁÉÍÓÚÑ ]+)\\)");
  private static final Pattern CLAVE =
      Pattern.compile("\\d+(?:\\s*[-–—./]\\s*\\d+|\\s+\\d+)*");
  private static final Pattern SEPARADOR_CLAVE = Pattern.compile("\\s*[-–—./]\\s*|\\s+");
  private static final Pattern UNIDAD_SUPERFICIE =
      Pattern.compile("(?i)\\s*(?:m²|m2|mts?\\.?\\s*2|metros\\s+cuadrados)\\.?$");
  private static final Pattern PREFIJO_MONEDA = Pattern.compile("(?i)^\\s*(?:USD|US\\$|\\$)\\s*");
  private static final Pattern SUFIJO_DOLARES = Pattern.compile("(?i)\\s*d[oó]lares\\b.*$");
  private static final Pattern ISO_CON_HORA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*");
  private static final List<DateTimeFormatter> FORMATOS_FECHA =
      List.of(
          formato("d/M/uuuu"), formato("d-M-uuuu"), formato("d.M.uuuu"), formato("uuuu-M-d"));
  private static final DateTimeFormatter FECHA_CORTA = formato("dd/MM/uuuu");

  private LetrasNotariales() {}

  /** {@code x_completo} → {@code x}; cualquier otro tag queda igual. */
  static String origen(String tag) {
    if (tag != null && tag.endsWith(SUFIJO)) {
      String base = tag.substring(0, tag.length() - SUFIJO.length());
      if (CONVERSIONES.containsKey(base)) {
        return base;
      }
    }
    return tag;
  }

  /**
   * Derivados {@code _completo} que pide la plantilla. Origen vacío: no se agrega (lo reporta
   * camposVacios). Origen no convertible: va tal cual, sin letras.
   */
  static Resultado completar(Map<String, Object> datos, Collection<String> tags) {
    Map<String, String> completos = new LinkedHashMap<>();
    List<String> sinConvertir = new ArrayList<>();
    for (String tag : tags) {
      String base = origen(tag);
      if (base.equals(tag) || completos.containsKey(tag)) {
        continue;
      }
      String valor = texto(datos.get(base));
      if (valor.isEmpty()) {
        continue;
      }
      Tipo tipo = CONVERSIONES.get(base);
      Optional<String> letras = convertir(tipo, valor, texto(datos.get(LETRAS_MONTO.get(base))));
      if (letras.isPresent()) {
        completos.put(tag, letras.get());
        continue;
      }
      completos.put(tag, talCual(tipo, valor));
      if (!traeLetras(tipo, valor) && !sinConvertir.contains(base)) {
        sinConvertir.add(base);
      }
    }
    return new Resultado(completos, sinConvertir);
  }

  static Optional<String> convertir(Tipo tipo, String valor, String letrasMonto) {
    String t = valor == null ? "" : valor.trim();
    if (t.isEmpty() || traeLetras(tipo, t)) {
      return Optional.empty();
    }
    return switch (tipo) {
      case DIGITOS -> digitos(t);
      case MONTO -> monto(t, letrasMonto);
      case SUPERFICIE -> superficie(t);
      case CLAVE_CATASTRAL -> claveCatastral(t);
      case FECHA -> parsearFecha(t).map(LetrasNotariales::fecha);
      case FECHA_OTORGAMIENTO -> parsearFecha(t).map(LetrasNotariales::fechaOtorgamiento);
    };
  }

  /**
   * Igual que la minuta del bufete ({@code MinutaViviendaData.digitosConLetras}): el número tal
   * cual y sus dígitos con {@link NumeroALetras#digitosALetras}: «0956856306 (CERO NUEVE …)».
   */
  static Optional<String> digitos(String valor) {
    String t = valor.trim();
    if (!NUMERO_DIGITOS.matcher(t).matches()) {
      return Optional.empty();
    }
    return Optional.of(t + " (" + NumeroALetras.digitosALetras(t) + ")");
  }

  /**
   * «USD 120.000,00 (CIENTO VEINTE MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)». Las
   * letras son las del payload (el bufete las arma con {@link NumeroALetras#montoBancario}); si no
   * vienen, las mismas de {@code montoBancario}.
   */
  static Optional<String> monto(String valor, String letrasPayload) {
    Optional<BigDecimal> cifra = NumeroALetras.parsearMonto(valor);
    if (cifra.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal monto = cifra.get().setScale(2, RoundingMode.HALF_UP);
    String letras =
        letrasPayload == null || letrasPayload.isBlank()
            ? NumeroALetras.montoBancario(monto)
            : letrasPayload.trim();
    letras = SUFIJO_DOLARES.matcher(letras).replaceFirst("").trim();
    if (!letras.contains("/100")) {
      letras += String.format(" con %02d/100", centesimos(monto));
    }
    return Optional.of(
        "USD " + cifra(monto) + " (" + letras.toUpperCase(Locale.ROOT) + " " + DOLARES + ")");
  }

  /**
   * Cédula o teléfono que {@code toTemplateMap()} ya trae como «0956 (CERO NUEVE CINCO SEIS)»:
   * devuelve solo el número, para las firmas y para armar su {@code _completo}. Otro valor, igual.
   */
  static Object soloNumero(String tag, Object valor) {
    if (CONVERSIONES.get(tag) != Tipo.DIGITOS || !(valor instanceof String texto)) {
      return valor;
    }
    var partes = CON_LETRAS_DEL_BUFETE.matcher(texto.trim());
    if (partes.matches() && partes.group(2).equals(NumeroALetras.digitosALetras(partes.group(1)))) {
      return partes.group(1).trim();
    }
    return valor;
  }

  /** «84,00 m² (OCHENTA METROS CUADRADOS CON OCHENTA DECÍMETROS CUADRADOS)». */
  static Optional<String> superficie(String valor) {
    String numero = UNIDAD_SUPERFICIE.matcher(valor.trim()).replaceFirst("").trim();
    Optional<BigDecimal> cifra = NumeroALetras.parsearMonto(numero);
    if (cifra.isEmpty() || !numero.matches("[0-9.,]+")) {
      return Optional.empty();
    }
    BigDecimal area = cifra.get().setScale(2, RoundingMode.HALF_UP);
    long metros = area.longValue();
    int decimetros = centesimos(area);
    StringBuilder letras =
        new StringBuilder(NumeroALetras.entero(metros))
            .append(metros == 1 ? " metro cuadrado" : " metros cuadrados");
    if (decimetros > 0) {
      letras
          .append(" con ")
          .append(NumeroALetras.entero(decimetros))
          .append(decimetros == 1 ? " decímetro cuadrado" : " decímetros cuadrados");
    }
    return Optional.of(cifra(area) + " m² (" + mayusculas(letras.toString()) + ")");
  }

  /** «60-2387-013 (SESENTA – DOS MIL TRESCIENTOS OCHENTA Y SIETE – CERO TRECE)». */
  static Optional<String> claveCatastral(String valor) {
    String t = valor.trim();
    if (!CLAVE.matcher(t).matches()) {
      return Optional.empty();
    }
    String[] segmentos = SEPARADOR_CLAVE.split(t);
    List<String> letras = new ArrayList<>();
    if (segmentos.length == 1) {
      // Sin separadores: dígito por dígito, no un cardinal de doce cifras.
      letras.add(NumeroALetras.digitosALetras(t));
    } else {
      for (String segmento : segmentos) {
        letras.add(segmento(segmento));
      }
    }
    return Optional.of(t + " (" + mayusculas(String.join(" – ", letras)) + ")");
  }

  /** «31/03/2026 (TREINTA Y UNO DE MARZO DE DOS MIL VEINTISÉIS)»; el día 1 es «PRIMERO». */
  static String fecha(LocalDate fecha) {
    String dia = fecha.getDayOfMonth() == 1 ? "primero" : cardinal(fecha.getDayOfMonth());
    String letras =
        dia + " de " + MESES[fecha.getMonthValue() - 1] + " de " + cardinal(fecha.getYear());
    return FECHA_CORTA.format(fecha) + " (" + mayusculas(letras) + ")";
  }

  /**
   * Como el texto original de la escritura: «a los veinte días del mes de agosto de dos mil
   * veintiséis»; el día 1, «al primer día del mes de …». Lleva la preposición: la plantilla no.
   */
  static String fechaOtorgamiento(LocalDate fecha) {
    String mesYAnio =
        " del mes de " + MESES[fecha.getMonthValue() - 1] + " de " + cardinal(fecha.getYear());
    if (fecha.getDayOfMonth() == 1) {
      return "al primer día" + mesYAnio;
    }
    // "días" es masculino: va con apócope ("veintiún días").
    return "a los " + NumeroALetras.entero(fecha.getDayOfMonth()) + " días" + mesYAnio;
  }

  /** {@code dd/MM/yyyy}, {@code d-M-yyyy}, {@code d.M.yyyy} o ISO (con o sin hora). */
  static Optional<LocalDate> parsearFecha(String valor) {
    if (valor == null || valor.isBlank()) {
      return Optional.empty();
    }
    String t = valor.trim();
    if (ISO_CON_HORA.matcher(t).matches()) {
      t = t.substring(0, 10);
    }
    for (DateTimeFormatter formato : FORMATOS_FECHA) {
      try {
        return Optional.of(LocalDate.parse(t, formato));
      } catch (DateTimeParseException ex) {
        // siguiente formato
      }
    }
    return Optional.empty();
  }

  /** Cardinal sin apócope, para cuando no va delante de un sustantivo: 21 → «veintiuno». */
  static String cardinal(long n) {
    String letras = NumeroALetras.entero(n);
    if (letras.endsWith("veintiún")) {
      return letras.substring(0, letras.length() - "veintiún".length()) + "veintiuno";
    }
    if (letras.equals("un") || letras.endsWith(" un")) {
      return letras + "o";
    }
    return letras;
  }

  /** Letras en mayúsculas, como las del bufete ({@link NumeroALetras#montoBancario}). */
  private static String mayusculas(String letras) {
    return letras.toUpperCase(ES);
  }

  /** Ceros a la izquierda uno por uno: «013» → «cero trece». */
  private static String segmento(String segmento) {
    if (segmento.length() > 12) {
      return NumeroALetras.digitosALetras(segmento);
    }
    int ceros = 0;
    while (ceros < segmento.length() && segmento.charAt(ceros) == '0') {
      ceros++;
    }
    List<String> palabras = new ArrayList<>();
    for (int i = 0; i < ceros; i++) {
      palabras.add("cero");
    }
    if (ceros < segmento.length()) {
      palabras.add(cardinal(Long.parseLong(segmento.substring(ceros))));
    }
    return String.join(" ", palabras);
  }

  private static String talCual(Tipo tipo, String valor) {
    String t = valor.trim();
    return switch (tipo) {
      case MONTO -> "USD " + PREFIJO_MONEDA.matcher(t).replaceFirst("");
      case FECHA_OTORGAMIENTO -> t.matches("(?i)^al?\\s.*") ? t : "a los " + t;
      default -> t;
    };
  }

  /** Letras propias del dato; la unidad de superficie, la moneda y la hora ISO no cuentan. */
  private static boolean traeLetras(Tipo tipo, String valor) {
    String t = valor.trim();
    switch (tipo) {
      case SUPERFICIE -> t = UNIDAD_SUPERFICIE.matcher(t).replaceFirst("");
      case MONTO -> t = PREFIJO_MONEDA.matcher(t).replaceFirst("");
      case FECHA, FECHA_OTORGAMIENTO -> {
        if (ISO_CON_HORA.matcher(t).matches()) {
          t = t.substring(0, 10);
        }
      }
      default -> {
        // sin prefijos ni unidades
      }
    }
    return LETRA.matcher(t).find();
  }

  private static int centesimos(BigDecimal valor) {
    return valor.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
  }

  /** 120.000,00: miles en punto, decimales en coma, siempre dos. Uno por llamada (no thread-safe). */
  private static String cifra(BigDecimal valor) {
    DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
    simbolos.setGroupingSeparator('.');
    simbolos.setDecimalSeparator(',');
    DecimalFormat formato = new DecimalFormat("#,##0.00", simbolos);
    formato.setRoundingMode(RoundingMode.HALF_UP);
    return formato.format(valor);
  }

  private static String texto(Object valor) {
    return valor == null ? "" : valor.toString().trim();
  }

  private static DateTimeFormatter formato(String patron) {
    return DateTimeFormatter.ofPattern(patron).withResolverStyle(ResolverStyle.STRICT);
  }
}
