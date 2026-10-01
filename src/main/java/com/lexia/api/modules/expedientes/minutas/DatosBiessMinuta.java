package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Datos del crédito tomados de la plataforma BIESS (captura o ingreso manual). No provienen de los
 * documentos del expediente. Los alias son el JSON de la captura ("DATOS APROBADOS PARA DESEMBOLSO").
 *
 * @param monto monto aprobado del préstamo
 * @param tasa tasa de interés efectiva anual
 * @param plazo plazo aprobado (meses o años)
 * @param cuota cuota mensual aprobada
 * @param valorReposicion valor de reposición del inmueble
 * @param porcentajeValorFinanciado porcentaje del valor financiado
 * @param apoderado apoderado especial del BIESS
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatosBiessMinuta(
    @JsonAlias("monto_aprobado") String monto,
    @JsonAlias("tasa_efectiva") String tasa,
    @JsonAlias("plazo_aprobado") String plazo,
    @JsonAlias({"cuota_aprobada", "cuota_credito"}) String cuota,
    @JsonAlias("valor_reposicion") String valorReposicion,
    @JsonAlias("porcentaje_valor_financiado") String porcentajeValorFinanciado,
    @JsonAlias("nombre_representante_biess") String apoderado) {

  @JsonCreator
  public DatosBiessMinuta {
    monto = clean(monto);
    tasa = clean(tasa);
    plazo = clean(plazo);
    cuota = clean(cuota);
    valorReposicion = clean(valorReposicion);
    porcentajeValorFinanciado = clean(porcentajeValorFinanciado);
    apoderado = clean(apoderado);
  }

  /** Compatibilidad con capturas previas a valor de reposición / porcentaje financiado. */
  public DatosBiessMinuta(String monto, String tasa, String plazo, String cuota, String apoderado) {
    this(monto, tasa, plazo, cuota, "", "", apoderado);
  }

  /** Compatibilidad con capturas previas a la cuota aprobada. */
  public DatosBiessMinuta(String monto, String tasa, String plazo, String apoderado) {
    this(monto, tasa, plazo, "", "", "", apoderado);
  }

  public static DatosBiessMinuta empty() {
    return new DatosBiessMinuta("", "", "", "", "", "", "");
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
        "",
        "",
        data.getApoderadoBiess());
  }

  public boolean isEmpty() {
    return monto.isEmpty()
        && tasa.isEmpty()
        && plazo.isEmpty()
        && cuota.isEmpty()
        && valorReposicion.isEmpty()
        && porcentajeValorFinanciado.isEmpty()
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
