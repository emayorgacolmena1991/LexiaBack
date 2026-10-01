package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Datos del crédito tomados de la plataforma BIESS (captura o ingreso manual). No provienen de los
 * documentos del expediente.
 *
 * @param monto monto aprobado del préstamo
 * @param tasa tasa de interés efectiva anual
 * @param plazo plazo aprobado (meses o años)
 * @param cuota cuota mensual aprobada
 * @param apoderado apoderado especial del BIESS
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatosBiessMinuta(
    String monto, String tasa, String plazo, String cuota, String apoderado) {

  @JsonCreator
  public DatosBiessMinuta {
    monto = clean(monto);
    tasa = clean(tasa);
    plazo = clean(plazo);
    cuota = clean(cuota);
    apoderado = clean(apoderado);
  }

  /** Compatibilidad con capturas previas a la cuota aprobada. */
  public DatosBiessMinuta(String monto, String tasa, String plazo, String apoderado) {
    this(monto, tasa, plazo, "", apoderado);
  }

  public static DatosBiessMinuta empty() {
    return new DatosBiessMinuta("", "", "", "", "");
  }

  public static DatosBiessMinuta from(MinutaViviendaData data) {
    if (data == null) {
      return empty();
    }
    return new DatosBiessMinuta(
        data.getMontoPrestamo(),
        data.getTasaInteresInicial(),
        data.getPlazoCredito(),
        data.getCuotaCredito(),
        data.getApoderadoBiess());
  }

  public boolean isEmpty() {
    return monto.isEmpty()
        && tasa.isEmpty()
        && plazo.isEmpty()
        && cuota.isEmpty()
        && apoderado.isEmpty();
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    String t = value.trim();
    return "nodata".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t) ? "" : t;
  }
}
