package com.lexia.api.modules.ia.ocr;

/** Resultado del check de legibilidad vía prebuilt-layout. */
public record CalidadDocumentoResultado(
    boolean legible,
    String estado,
    double promedioConfianza,
    int totalPalabras,
    double umbral,
    String mensaje) {

  public static final double UMBRAL_DEFAULT = 0.75;

  public static CalidadDocumentoResultado ilegible() {
    return new CalidadDocumentoResultado(
        false,
        "ILEGIBLE",
        0.0,
        0,
        UMBRAL_DEFAULT,
        "Ilegible. No hay texto o imagen muy oscura.");
  }

  public static CalidadDocumentoResultado dePromedio(double promedio, int totalPalabras) {
    if (promedio < UMBRAL_DEFAULT) {
      return new CalidadDocumentoResultado(
          false,
          "MALA_CALIDAD",
          promedio,
          totalPalabras,
          UMBRAL_DEFAULT,
          "Mala calidad / borroso. Pedir reescaneo.");
    }
    return new CalidadDocumentoResultado(
        true,
        "LEGIBLE",
        promedio,
        totalPalabras,
        UMBRAL_DEFAULT,
        "Documento legible. Pasar a extracción de datos.");
  }
}
