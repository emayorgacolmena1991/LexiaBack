package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

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

  /**
   * Único mapeo captura BIESS → tag canónico de la plantilla. Valor de reposición y porcentaje
   * financiado no tienen tag en la minuta.
   */
  public enum CampoPlantilla {
    MONTO("monto", "monto_prestamo", DatosBiessMinuta::monto, MinutaViviendaData::setMontoPrestamo),
    TASA(
        "tasa",
        "tasa_interes_inicial",
        DatosBiessMinuta::tasa,
        MinutaViviendaData::setTasaInteresInicial),
    PLAZO("plazo", "plazo_credito", DatosBiessMinuta::plazo, MinutaViviendaData::setPlazoCredito),
    CUOTA("cuota", "cuota_credito", DatosBiessMinuta::cuota, MinutaViviendaData::setCuotaCredito),
    APODERADO(
        "apoderado",
        "apoderado_biess",
        DatosBiessMinuta::apoderado,
        MinutaViviendaData::setApoderadoBiess);

    private final String propiedad;
    private final String tag;
    private final Function<DatosBiessMinuta, String> lector;
    private final BiConsumer<MinutaViviendaData, String> escritor;

    CampoPlantilla(
        String propiedad,
        String tag,
        Function<DatosBiessMinuta, String> lector,
        BiConsumer<MinutaViviendaData, String> escritor) {
      this.propiedad = propiedad;
      this.tag = tag;
      this.lector = lector;
      this.escritor = escritor;
    }

    /** Nombre del campo en el JSON de {@link DatosBiessMinuta}. */
    public String propiedad() {
      return propiedad;
    }

    public String tag() {
      return tag;
    }

    public String valor(DatosBiessMinuta datos) {
      return datos == null ? "" : lector.apply(datos);
    }

    public void asignar(MinutaViviendaData data, String valor) {
      escritor.accept(data, valor);
    }

    public static Optional<CampoPlantilla> porTag(String tag) {
      return Arrays.stream(values()).filter(c -> c.tag.equals(tag)).findFirst();
    }

    /** propiedad BIESS → tag, para el frontend. */
    public static Map<String, String> mapa() {
      Map<String, String> out = new LinkedHashMap<>();
      for (CampoPlantilla c : values()) {
        out.put(c.propiedad, c.tag);
      }
      return out;
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    String t = value.trim();
    return "nodata".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t) ? "" : t;
  }
}
