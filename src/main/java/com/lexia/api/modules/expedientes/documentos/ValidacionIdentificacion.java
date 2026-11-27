package com.lexia.api.modules.expedientes.documentos;

/** Valida cédula ecuatoriana (10 dígitos) o RUC de persona natural (13 dígitos, termina en 001). */
public final class ValidacionIdentificacion {

  private ValidacionIdentificacion() {}

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
    if (digits.charAt(2) - '0' >= 6) {
      return false;
    }
    int suma = 0;
    for (int i = 0; i < 9; i++) {
      int d = (digits.charAt(i) - '0') * ((i % 2 == 0) ? 2 : 1);
      suma += d > 9 ? d - 9 : d;
    }
    return (10 - suma % 10) % 10 == digits.charAt(9) - '0';
  }

  static String soloDigitos(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }
}
