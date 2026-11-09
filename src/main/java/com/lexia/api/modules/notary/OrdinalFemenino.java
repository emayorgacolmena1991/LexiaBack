package com.lexia.api.modules.notary;

import java.util.Optional;

/** Ordinal femenino en mayúsculas para el número de una notaría: 61 → «SEXAGÉSIMA PRIMERA». */
final class OrdinalFemenino {

  private static final String[] UNIDADES = {
    "", "PRIMERA", "SEGUNDA", "TERCERA", "CUARTA", "QUINTA", "SEXTA", "SÉPTIMA", "OCTAVA", "NOVENA"
  };
  private static final String[] DECENAS = {
    "",
    "DÉCIMA",
    "VIGÉSIMA",
    "TRIGÉSIMA",
    "CUADRAGÉSIMA",
    "QUINCUAGÉSIMA",
    "SEXAGÉSIMA",
    "SEPTUAGÉSIMA",
    "OCTOGÉSIMA",
    "NONAGÉSIMA"
  };

  private OrdinalFemenino() {}

  /** Vacío si el texto no es un entero de 1 a 99. */
  static Optional<String> de(String valor) {
    int numero;
    try {
      numero = Integer.parseInt(valor == null ? "" : valor.trim());
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
    if (numero < 1 || numero > 99) {
      return Optional.empty();
    }
    if (numero == 11) {
      return Optional.of("UNDÉCIMA");
    }
    if (numero == 12) {
      return Optional.of("DUODÉCIMA");
    }
    String decena = DECENAS[numero / 10];
    String unidad = UNIDADES[numero % 10];
    return Optional.of(decena.isEmpty() ? unidad : unidad.isEmpty() ? decena : decena + " " + unidad);
  }
}
