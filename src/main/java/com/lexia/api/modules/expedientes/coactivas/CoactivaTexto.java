package com.lexia.api.modules.expedientes.coactivas;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalización de datos que llegan de actas, bases Excel y nombres de archivo. */
public final class CoactivaTexto {

  /** N.º de juicio coactivo tal como aparece en los PDFs: {@code 025-2024-00026}. */
  private static final Pattern JUICIO = Pattern.compile("(?<!\\d)(\\d{2,4})\\s*-\\s*(\\d{4})\\s*-\\s*(\\d{2,6})(?!\\d)");

  private CoactivaTexto() {}

  public static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  public static String sinTildes(String value) {
    if (value == null) {
      return "";
    }
    return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
  }

  /** Mayúsculas, sin tildes y con espacios colapsados: clave de comparación de nombres. */
  public static String claveNombre(String value) {
    return sinTildes(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9Ñ ]", " ").replaceAll("\\s+", " ").trim();
  }

  public static String nombrePropio(String value) {
    String clean = blankToNull(value);
    if (clean == null) {
      return null;
    }
    return clean.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  public static String soloDigitos(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  /** Cédula ecuatoriana (10 dígitos, módulo 10) o RUC de persona natural (13 dígitos, termina en 001). */
  public static boolean cedulaValida(String identificacion) {
    String digits = soloDigitos(identificacion);
    if (digits.length() == 13 && digits.endsWith("001")) {
      digits = digits.substring(0, 10);
    }
    if (digits.length() != 10) {
      return false;
    }
    int provincia = Integer.parseInt(digits.substring(0, 2));
    if ((provincia < 1 || provincia > 24) && provincia != 30) {
      return false;
    }
    int tercero = digits.charAt(2) - '0';
    if (tercero >= 6) {
      return false;
    }
    int suma = 0;
    for (int i = 0; i < 9; i++) {
      int d = (digits.charAt(i) - '0') * ((i % 2 == 0) ? 2 : 1);
      suma += d > 9 ? d - 9 : d;
    }
    int verificador = (10 - (suma % 10)) % 10;
    return verificador == digits.charAt(9) - '0';
  }

  public static String normalizarIdentificacion(String value) {
    String clean = blankToNull(value);
    if (clean == null) {
      return null;
    }
    String digits = soloDigitos(clean);
    if (digits.length() == 9) {
      digits = "0" + digits;
    }
    if (digits.length() == 10 || digits.length() == 13) {
      return digits;
    }
    return truncate(clean.replaceAll("\\s+", "").toUpperCase(Locale.ROOT), 20);
  }

  public static Optional<String> detectarJuicio(String texto) {
    if (texto == null) {
      return Optional.empty();
    }
    Matcher matcher = JUICIO.matcher(texto);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return Optional.of(formatearJuicio(matcher.group(1), matcher.group(2), matcher.group(3)));
  }

  /** Canoniza un n.º de juicio escrito con o sin ceros/espacios a {@code 025-2024-00026}. */
  public static String normalizarJuicio(String value) {
    String clean = blankToNull(value);
    if (clean == null) {
      return null;
    }
    return detectarJuicio(clean).orElse(truncate(clean.replaceAll("\\s+", "").toUpperCase(Locale.ROOT), 40));
  }

  private static String formatearJuicio(String oficina, String anio, String secuencia) {
    String of = String.format("%03d", Integer.parseInt(oficina));
    String seq = String.format("%05d", Integer.parseInt(secuencia));
    return of + "-" + anio + "-" + seq;
  }

  public static Integer anioDeJuicio(String juicio) {
    if (juicio == null) {
      return null;
    }
    Matcher matcher = JUICIO.matcher(juicio);
    return matcher.find() ? Integer.parseInt(matcher.group(2)) : null;
  }

  public static Integer parseEntero(String value) {
    String digits = soloDigitos(value);
    if (digits.isEmpty() || digits.length() > 9) {
      return null;
    }
    return Integer.parseInt(digits);
  }

  public static String safeFileName(String name) {
    String base = name == null || name.isBlank() ? "archivo" : name;
    base = sinTildes(base).replaceAll("[^A-Za-z0-9._-]", "_");
    return truncate(base, 120);
  }
}
