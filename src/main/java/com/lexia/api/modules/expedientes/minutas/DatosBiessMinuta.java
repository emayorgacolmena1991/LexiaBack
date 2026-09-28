package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Datos del crédito tomados de la plataforma BIESS (captura o ingreso manual). No provienen de los
 * documentos del expediente.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatosBiessMinuta(String monto, String tasa, String plazo, String apoderado) {

  public DatosBiessMinuta {
    monto = clean(monto);
    tasa = clean(tasa);
    plazo = clean(plazo);
    apoderado = clean(apoderado);
  }

  public static DatosBiessMinuta empty() {
    return new DatosBiessMinuta("", "", "", "");
  }

  public static DatosBiessMinuta from(MinutaViviendaData data) {
    if (data == null) {
      return empty();
    }
    return new DatosBiessMinuta(
        data.getMontoPrestamo(),
        data.getTasaInteresInicial(),
        data.getPlazoCredito(),
        data.getApoderadoBiess());
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    String t = value.trim();
    return "nodata".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t) ? "" : t;
  }
}
