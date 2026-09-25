package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Datos estructurados para plantillas de vivienda hipotecada BIESS (poi-tl + LLM tool use).
 * Keys snake_case = tags {@code {{...}}} en el .docx.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MinutaViviendaData {

  @JsonProperty("nombre_conyuge_1")
  private String nombreConyuge1 = "";

  @JsonProperty("cedula_conyuge_1")
  private String cedulaConyuge1 = "";

  @JsonProperty("nombre_conyuge_2")
  private String nombreConyuge2 = "";

  @JsonProperty("cedula_conyuge_2")
  private String cedulaConyuge2 = "";

  @JsonProperty("profesion_conyuge_1")
  private String profesionConyuge1 = "";

  @JsonProperty("profesion_conyuge_2")
  private String profesionConyuge2 = "";

  @JsonProperty("canton_domicilio")
  private String cantonDomicilio = "";

  @JsonProperty("nombre_afiliado")
  private String nombreAfiliado = "";

  @JsonProperty("descripcion_inmuebles_antecedentes")
  private String descripcionInmueblesAntecedentes = "";

  @JsonProperty("descripcion_inmueble_hipoteca")
  private String descripcionInmuebleHipoteca = "";

  @JsonProperty("parroquia_inmueble")
  private String parroquiaInmueble = "";

  @JsonProperty("canton_inmueble")
  private String cantonInmueble = "";

  @JsonProperty("provincia_inmueble")
  private String provinciaInmueble = "";

  @JsonProperty("lindero_norte")
  private String linderoNorte = "";

  @JsonProperty("lindero_sur")
  private String linderoSur = "";

  @JsonProperty("lindero_este")
  private String linderoEste = "";

  @JsonProperty("lindero_oeste")
  private String linderoOeste = "";

  @JsonProperty("superficie_m2")
  private String superficieM2 = "";

  // --- Contrato de mutuo / sustitución (tags adicionales) ---

  @JsonProperty("estado_civil")
  private String estadoCivil = "";

  @JsonProperty("monto_prestamo")
  private String montoPrestamo = "";

  @JsonProperty("monto_prestamo_letras")
  private String montoPrestamoLetras = "";

  @JsonProperty("plazo_credito")
  private String plazoCredito = "";

  @JsonProperty("tasa_interes_inicial")
  private String tasaInteresInicial = "";

  @JsonProperty("institucion_financiera_original")
  private String institucionFinancieraOriginal = "";

  @JsonProperty("direccion_deudor")
  private String direccionDeudor = "";

  @JsonProperty("telefono_deudor")
  private String telefonoDeudor = "";

  @JsonProperty("correo_deudor")
  private String correoDeudor = "";

  @JsonProperty("ciudad_firma")
  private String ciudadFirma = "";

  @JsonProperty("fecha_firma")
  private String fechaFirma = "";

  public String getNombreConyuge1() {
    return nombreConyuge1;
  }

  public void setNombreConyuge1(String nombreConyuge1) {
    this.nombreConyuge1 = nullToEmpty(nombreConyuge1);
  }

  public String getCedulaConyuge1() {
    return cedulaConyuge1;
  }

  public void setCedulaConyuge1(String cedulaConyuge1) {
    this.cedulaConyuge1 = nullToEmpty(cedulaConyuge1);
  }

  public String getNombreConyuge2() {
    return nombreConyuge2;
  }

  public void setNombreConyuge2(String nombreConyuge2) {
    this.nombreConyuge2 = nullToEmpty(nombreConyuge2);
  }

  public String getCedulaConyuge2() {
    return cedulaConyuge2;
  }

  public void setCedulaConyuge2(String cedulaConyuge2) {
    this.cedulaConyuge2 = nullToEmpty(cedulaConyuge2);
  }

  public String getProfesionConyuge1() {
    return profesionConyuge1;
  }

  public void setProfesionConyuge1(String profesionConyuge1) {
    this.profesionConyuge1 = nullToEmpty(profesionConyuge1);
  }

  public String getProfesionConyuge2() {
    return profesionConyuge2;
  }

  public void setProfesionConyuge2(String profesionConyuge2) {
    this.profesionConyuge2 = nullToEmpty(profesionConyuge2);
  }

  public String getCantonDomicilio() {
    return cantonDomicilio;
  }

  public void setCantonDomicilio(String cantonDomicilio) {
    this.cantonDomicilio = nullToEmpty(cantonDomicilio);
  }

  public String getNombreAfiliado() {
    return nombreAfiliado;
  }

  public void setNombreAfiliado(String nombreAfiliado) {
    this.nombreAfiliado = nullToEmpty(nombreAfiliado);
  }

  public String getDescripcionInmueblesAntecedentes() {
    return descripcionInmueblesAntecedentes;
  }

  public void setDescripcionInmueblesAntecedentes(String descripcionInmueblesAntecedentes) {
    this.descripcionInmueblesAntecedentes = nullToEmpty(descripcionInmueblesAntecedentes);
  }

  public String getDescripcionInmuebleHipoteca() {
    return descripcionInmuebleHipoteca;
  }

  public void setDescripcionInmuebleHipoteca(String descripcionInmuebleHipoteca) {
    this.descripcionInmuebleHipoteca = nullToEmpty(descripcionInmuebleHipoteca);
  }

  public String getParroquiaInmueble() {
    return parroquiaInmueble;
  }

  public void setParroquiaInmueble(String parroquiaInmueble) {
    this.parroquiaInmueble = nullToEmpty(parroquiaInmueble);
  }

  public String getCantonInmueble() {
    return cantonInmueble;
  }

  public void setCantonInmueble(String cantonInmueble) {
    this.cantonInmueble = nullToEmpty(cantonInmueble);
  }

  public String getProvinciaInmueble() {
    return provinciaInmueble;
  }

  public void setProvinciaInmueble(String provinciaInmueble) {
    this.provinciaInmueble = nullToEmpty(provinciaInmueble);
  }

  public String getLinderoNorte() {
    return linderoNorte;
  }

  public void setLinderoNorte(String linderoNorte) {
    this.linderoNorte = nullToEmpty(linderoNorte);
  }

  public String getLinderoSur() {
    return linderoSur;
  }

  public void setLinderoSur(String linderoSur) {
    this.linderoSur = nullToEmpty(linderoSur);
  }

  public String getLinderoEste() {
    return linderoEste;
  }

  public void setLinderoEste(String linderoEste) {
    this.linderoEste = nullToEmpty(linderoEste);
  }

  public String getLinderoOeste() {
    return linderoOeste;
  }

  public void setLinderoOeste(String linderoOeste) {
    this.linderoOeste = nullToEmpty(linderoOeste);
  }

  public String getSuperficieM2() {
    return superficieM2;
  }

  public void setSuperficieM2(String superficieM2) {
    this.superficieM2 = nullToEmpty(superficieM2);
  }

  public String getEstadoCivil() {
    return estadoCivil;
  }

  public void setEstadoCivil(String estadoCivil) {
    this.estadoCivil = nullToEmpty(estadoCivil);
  }

  public String getMontoPrestamo() {
    return montoPrestamo;
  }

  public void setMontoPrestamo(String montoPrestamo) {
    this.montoPrestamo = nullToEmpty(montoPrestamo);
  }

  public String getMontoPrestamoLetras() {
    return montoPrestamoLetras;
  }

  public void setMontoPrestamoLetras(String montoPrestamoLetras) {
    this.montoPrestamoLetras = nullToEmpty(montoPrestamoLetras);
  }

  public String getPlazoCredito() {
    return plazoCredito;
  }

  public void setPlazoCredito(String plazoCredito) {
    this.plazoCredito = nullToEmpty(plazoCredito);
  }

  public String getTasaInteresInicial() {
    return tasaInteresInicial;
  }

  public void setTasaInteresInicial(String tasaInteresInicial) {
    this.tasaInteresInicial = nullToEmpty(tasaInteresInicial);
  }

  public String getInstitucionFinancieraOriginal() {
    return institucionFinancieraOriginal;
  }

  public void setInstitucionFinancieraOriginal(String institucionFinancieraOriginal) {
    this.institucionFinancieraOriginal = nullToEmpty(institucionFinancieraOriginal);
  }

  public String getDireccionDeudor() {
    return direccionDeudor;
  }

  public void setDireccionDeudor(String direccionDeudor) {
    this.direccionDeudor = nullToEmpty(direccionDeudor);
  }

  public String getTelefonoDeudor() {
    return telefonoDeudor;
  }

  public void setTelefonoDeudor(String telefonoDeudor) {
    this.telefonoDeudor = nullToEmpty(telefonoDeudor);
  }

  public String getCorreoDeudor() {
    return correoDeudor;
  }

  public void setCorreoDeudor(String correoDeudor) {
    this.correoDeudor = nullToEmpty(correoDeudor);
  }

  public String getCiudadFirma() {
    return ciudadFirma;
  }

  public void setCiudadFirma(String ciudadFirma) {
    this.ciudadFirma = nullToEmpty(ciudadFirma);
  }

  public String getFechaFirma() {
    return fechaFirma;
  }

  public void setFechaFirma(String fechaFirma) {
    this.fechaFirma = nullToEmpty(fechaFirma);
  }

  /**
   * Mapa listo para poi-tl (keys = tags del .docx). Valores vacíos → {@code nodata}.
   */
  public Map<String, Object> toTemplateMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("nombre_conyuge_1", blankToNodata(nombreConyuge1));
    map.put("cedula_conyuge_1", blankToNodata(cedulaConyuge1));
    map.put("nombre_conyuge_2", blankToNodata(nombreConyuge2));
    map.put("cedula_conyuge_2", blankToNodata(cedulaConyuge2));
    map.put("profesion_conyuge_1", blankToNodata(profesionConyuge1));
    map.put("profesion_conyuge_2", blankToNodata(profesionConyuge2));
    map.put("canton_domicilio", blankToNodata(cantonDomicilio));
    map.put("nombre_afiliado", blankToNodata(nombreAfiliado));
    map.put("descripcion_inmuebles_antecedentes", blankToNodata(descripcionInmueblesAntecedentes));
    map.put("descripcion_inmueble_hipoteca", blankToNodata(descripcionInmuebleHipoteca));
    map.put("parroquia_inmueble", blankToNodata(parroquiaInmueble));
    map.put("canton_inmueble", blankToNodata(cantonInmueble));
    map.put("provincia_inmueble", blankToNodata(provinciaInmueble));
    map.put("lindero_norte", blankToNodata(linderoNorte));
    map.put("lindero_sur", blankToNodata(linderoSur));
    map.put("lindero_este", blankToNodata(linderoEste));
    map.put("lindero_oeste", blankToNodata(linderoOeste));
    map.put("superficie_m2", blankToNodata(superficieM2));
    map.put("estado_civil", blankToNodata(estadoCivil));
    map.put("monto_prestamo", blankToNodata(montoPrestamo));
    map.put("monto_prestamo_letras", blankToNodata(montoPrestamoLetras));
    map.put("plazo_credito", blankToNodata(plazoCredito));
    map.put("tasa_interes_inicial", blankToNodata(tasaInteresInicial));
    map.put("institucion_financiera_original", blankToNodata(institucionFinancieraOriginal));
    map.put("direccion_deudor", blankToNodata(direccionDeudor));
    map.put("telefono_deudor", blankToNodata(telefonoDeudor));
    map.put("correo_deudor", blankToNodata(correoDeudor));
    map.put("ciudad_firma", blankToNodata(ciudadFirma));
    map.put("fecha_firma", blankToNodata(fechaFirma));
    return map;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNodata(String value) {
    String t = nullToEmpty(value);
    if (t.isEmpty() || "null".equalsIgnoreCase(t) || "n/a".equalsIgnoreCase(t)) {
      return "nodata";
    }
    return t;
  }
}
