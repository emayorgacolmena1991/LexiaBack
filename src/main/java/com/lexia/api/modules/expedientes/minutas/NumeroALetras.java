package com.lexia.api.modules.expedientes.minutas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Montos en letras (español) para las plantillas: {@code 85000 → "ochenta y cinco mil"}, {@code
 * 85000.50 → "ochenta y cinco mil con 50/100"}. Usa la forma apocopada ("un", "veintiún") porque
 * en las plantillas siempre va seguido de "Dólares".
 */
public final class NumeroALetras {

  private static final long MAXIMO = 999_999_999_999L;

  private static final String[] UNIDADES = {
    "", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
    "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho",
    "diecinueve", "veinte", "veintiún", "veintidós", "veintitrés", "veinticuatro", "veinticinco",
    "veintiséis", "veintisiete", "veintiocho", "veintinueve"
  };
  private static final String[] DECENAS = {
    "", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"
  };
  private static final String[] CENTENAS = {
    "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos",
    "setecientos", "ochocientos", "novecientos"
  };

  private static final Pattern PREFIJO_MONEDA = Pattern.compile("(?i)^(USD|US\\$|\\$)+");
  private static final Pattern SOLO_CIFRA = Pattern.compile("[0-9.,]+");
  private static final Pattern ENTERO_SIN_GRUPOS = Pattern.compile("\\d+");
  private static final Pattern ENTERO_AGRUPADO = Pattern.compile("\\d{1,3}(?:([.,])\\d{3})(?:\\1\\d{3})*");

  private NumeroALetras() {}

  /** Monto en letras con centavos ("con 50/100") si los hay; vacío si no se puede interpretar. */
  public static String monto(String raw) {
    return parsearMonto(raw).map(NumeroALetras::monto).orElse("");
  }

  public static String monto(BigDecimal valor) {
    BigDecimal redondeado = valor.setScale(2, RoundingMode.HALF_UP);
    long entero = redondeado.longValue();
    int centavos = redondeado.remainder(BigDecimal.ONE).movePointRight(2).intValue();
    String letras = entero(entero);
    return centavos == 0 ? letras : letras + String.format(" con %02d/100", centavos);
  }

  /**
   * Interpreta "85,000.00", "85.000,00", "85000", "$ 85.000,50": el último separador seguido de
   * 1-2 dígitos es el decimal; el resto son de miles (agrupados de a tres, un solo tipo).
   */
  public static Optional<BigDecimal> parsearMonto(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String s = raw.replaceAll("[\\s\\u00A0]", "");
    s = PREFIJO_MONEDA.matcher(s).replaceFirst("");
    if (s.isEmpty() || !SOLO_CIFRA.matcher(s).matches() || !s.matches(".*\\d.*")) {
      return Optional.empty();
    }
    String enteros = s;
    String decimales = "";
    int ultimo = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
    if (ultimo >= 0) {
      String cola = s.substring(ultimo + 1);
      if (cola.matches("\\d{1,2}")) {
        enteros = s.substring(0, ultimo);
        decimales = cola;
        char sepDecimal = s.charAt(ultimo);
        if (enteros.indexOf(sepDecimal) >= 0) {
          return Optional.empty();
        }
      }
    }
    if (enteros.isEmpty()) {
      enteros = "0";
    }
    if (!ENTERO_SIN_GRUPOS.matcher(enteros).matches()
        && !ENTERO_AGRUPADO.matcher(enteros).matches()) {
      return Optional.empty();
    }
    BigDecimal valor =
        new BigDecimal(enteros.replaceAll("[.,]", "") + (decimales.isEmpty() ? "" : "." + decimales));
    return valor.compareTo(BigDecimal.valueOf(MAXIMO)) > 0 ? Optional.empty() : Optional.of(valor);
  }

  /** Parte entera en letras (0 → "cero", 1000 → "mil", 1000000 → "un millón"). */
  public static String entero(long n) {
    if (n < 0 || n > MAXIMO) {
      throw new IllegalArgumentException("Fuera de rango: " + n);
    }
    if (n == 0) {
      return "cero";
    }
    long millones = n / 1_000_000;
    long resto = n % 1_000_000;
    StringBuilder out = new StringBuilder();
    if (millones == 1) {
      out.append("un millón");
    } else if (millones > 1) {
      out.append(hastaMillon(millones)).append(" millones");
    }
    if (resto > 0) {
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(hastaMillon(resto));
    }
    return out.toString();
  }

  private static String hastaMillon(long n) {
    int miles = (int) (n / 1000);
    int resto = (int) (n % 1000);
    StringBuilder out = new StringBuilder();
    if (miles == 1) {
      out.append("mil");
    } else if (miles > 1) {
      out.append(hastaMil(miles)).append(" mil");
    }
    if (resto > 0) {
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(hastaMil(resto));
    }
    return out.toString();
  }

  private static String hastaMil(int n) {
    if (n == 100) {
      return "cien";
    }
    int centenas = n / 100;
    int resto = n % 100;
    String decenas = hastaCien(resto);
    if (centenas == 0) {
      return decenas;
    }
    return decenas.isEmpty() ? CENTENAS[centenas] : CENTENAS[centenas] + " " + decenas;
  }

  private static String hastaCien(int n) {
    if (n < 30) {
      return UNIDADES[n];
    }
    int unidad = n % 10;
    return unidad == 0 ? DECENAS[n / 10] : DECENAS[n / 10] + " y " + UNIDADES[unidad];
  }
}
