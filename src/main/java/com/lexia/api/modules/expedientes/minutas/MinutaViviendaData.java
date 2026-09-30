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

  @JsonProperty("area_solar")
  private String areaSolar = "";

  @JsonProperty("area_construccion")
  private String areaConstruccion = "";

  @JsonProperty("area_util")
  private String areaUtil = "";

  @JsonProperty("area_comun")
  private String areaComun = "";

  @JsonProperty("alicuota")
  private String alicuota = "";

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

  /** Captura BIESS / ingreso manual. Lo usan las plantillas de Vivienda Terminada Preferencial. */
  @JsonProperty("apoderado_biess")
  private String apoderadoBiess = "";

  @JsonProperty("cedula_apoderado_biess")
  private String cedulaApoderadoBiess = "";

  // --- Parte vendedora (compraventa). Nombre/cédula/estado civil también en el consolidado IA ---

  @JsonProperty("nombre_vendedor")
  private String nombreVendedor = "";

  @JsonProperty("cedula_vendedor")
  private String cedulaVendedor = "";

  @JsonProperty("estado_civil_vendedor")
  private String estadoCivilVendedor = "";

  @JsonProperty("nombre_conyuge_vendedor")
  private String nombreConyugeVendedor = "";

  @JsonProperty("cedula_conyuge_vendedor")
  private String cedulaConyugeVendedor = "";

  @JsonProperty("profesion_vendedor")
  private String profesionVendedor = "";

  @JsonProperty("direccion_vendedor")
  private String direccionVendedor = "";

  @JsonProperty("telefono_vendedor")
  private String telefonoVendedor = "";

  @JsonProperty("correo_vendedor")
  private String correoVendedor = "";

  // --- Inmueble (consolidado IA: inmueble.claveCatastral / inmueble.avaluo) ---

  @JsonProperty("clave_catastral")
  private String claveCatastral = "";

  @JsonProperty("avaluo_inmueble")
  private String avaluoInmueble = "";

  // --- Precio y forma de pago de la compraventa ---

  @JsonProperty("precio_compraventa_numero")
  private String precioCompraventaNumero = "";

  @JsonProperty("precio_compraventa_letras")
  private String precioCompraventaLetras = "";

  @JsonProperty("valor_entrada_numero")
  private String valorEntradaNumero = "";

  @JsonProperty("valor_entrada_letras")
  private String valorEntradaLetras = "";

  @JsonProperty("saldo_compraventa_numero")
  private String saldoCompraventaNumero = "";

  @JsonProperty("saldo_compraventa_letras")
  private String saldoCompraventaLetras = "";

  // --- Antecedente de dominio del vendedor ---

  @JsonProperty("fecha_escritura_antecedente")
  private String fechaEscrituraAntecedente = "";

  @JsonProperty("fecha_inscripcion_antecedente")
  private String fechaInscripcionAntecedente = "";

  @JsonProperty("repertorio_antecedente")
  private String repertorioAntecedente = "";

  @JsonProperty("notaria_antecedente")
  private String notariaAntecedente = "";

  // --- Vivienda Hipotecada BIESS: sin captura aún, quedan como nodata / camposPendientes ---

  @JsonProperty("representacion_sociedad_conyugal_vendedor")
  private String representacionSociedadConyugalVendedor = "";

  @JsonProperty("representacion_sociedad_conyugal_comprador")
  private String representacionSociedadConyugalComprador = "";

  @JsonProperty("celular_vendedor")
  private String celularVendedor = "";

  @JsonProperty("celular_comprador")
  private String celularComprador = "";

  @JsonProperty("canton_notario_adquisicion")
  private String cantonNotarioAdquisicion = "";

  @JsonProperty("nombre_adquirente_anterior")
  private String nombreAdquirenteAnterior = "";

  @JsonProperty("estado_civil_adquirente_anterior")
  private String estadoCivilAdquirenteAnterior = "";

  @JsonProperty("descripcion_inmueble_general")
  private String descripcionInmuebleGeneral = "";

  @JsonProperty("nombre_conjunto_edificio")
  private String nombreConjuntoEdificio = "";

  @JsonProperty("modo_propiedad_horizontal")
  private String modoPropiedadHorizontal = "";

  @JsonProperty("fecha_escritura_ph")
  private String fechaEscrituraPh = "";

  @JsonProperty("notario_ph")
  private String notarioPh = "";

  @JsonProperty("canton_notario_ph")
  private String cantonNotarioPh = "";

  @JsonProperty("canton_registro_ph")
  private String cantonRegistroPh = "";

  @JsonProperty("fecha_inscripcion_ph")
  private String fechaInscripcionPh = "";

  @JsonProperty("fecha_escritura_antecedente_tres")
  private String fechaEscrituraAntecedenteTres = "";

  @JsonProperty("notario_antecedente_tres")
  private String notarioAntecedenteTres = "";

  @JsonProperty("canton_notario_antecedente_tres")
  private String cantonNotarioAntecedenteTres = "";

  @JsonProperty("canton_registro_antecedente_tres")
  private String cantonRegistroAntecedenteTres = "";

  @JsonProperty("fecha_inscripcion_antecedente_tres")
  private String fechaInscripcionAntecedenteTres = "";

  @JsonProperty("linderos_generales_norte")
  private String linderosGeneralesNorte = "";

  @JsonProperty("linderos_generales_sur")
  private String linderosGeneralesSur = "";

  @JsonProperty("linderos_generales_este")
  private String linderosGeneralesEste = "";

  @JsonProperty("linderos_generales_oeste")
  private String linderosGeneralesOeste = "";

  @JsonProperty("superficie_general")
  private String superficieGeneral = "";

  @JsonProperty("detalles_linderos_especificos_completos")
  private String detallesLinderosEspecificosCompletos = "";

  @JsonProperty("detalle_forma_pago")
  private String detalleFormaPago = "";

  @JsonProperty("art_y_deudor_2")
  private String artYDeudor2 = "";

  @JsonProperty("calidad_afiliado")
  private String calidadAfiliado = "";

  /** Etiquetas legibles para reportar campos faltantes. Orden = orden de {@link #toTemplateMap()}. */
  private static final Map<String, String> ETIQUETAS = new LinkedHashMap<>();

  static {
    ETIQUETAS.put("nombre_conyuge_1", "Nombre del comprador / deudor");
    ETIQUETAS.put("cedula_conyuge_1", "Cédula del comprador / deudor");
    ETIQUETAS.put("nombre_conyuge_2", "Nombre del cónyuge del comprador");
    ETIQUETAS.put("cedula_conyuge_2", "Cédula del cónyuge del comprador");
    ETIQUETAS.put("profesion_conyuge_1", "Profesión del comprador");
    ETIQUETAS.put("profesion_conyuge_2", "Profesión del cónyuge del comprador");
    ETIQUETAS.put("canton_domicilio", "Cantón de domicilio del deudor");
    ETIQUETAS.put("nombre_afiliado", "Nombre del afiliado BIESS");
    ETIQUETAS.put("descripcion_inmuebles_antecedentes", "Descripción del inmueble (antecedentes)");
    ETIQUETAS.put("descripcion_inmueble_hipoteca", "Descripción del inmueble hipotecado");
    ETIQUETAS.put("parroquia_inmueble", "Parroquia del inmueble");
    ETIQUETAS.put("canton_inmueble", "Cantón del inmueble");
    ETIQUETAS.put("provincia_inmueble", "Provincia del inmueble");
    ETIQUETAS.put("lindero_norte", "Lindero norte");
    ETIQUETAS.put("lindero_sur", "Lindero sur");
    ETIQUETAS.put("lindero_este", "Lindero este");
    ETIQUETAS.put("lindero_oeste", "Lindero oeste");
    ETIQUETAS.put("superficie_m2", "Superficie (m2)");
    ETIQUETAS.put("area_solar", "Área de solar (m2)");
    ETIQUETAS.put("area_construccion", "Área de construcción (m2)");
    ETIQUETAS.put("area_util", "Área útil (m2)");
    ETIQUETAS.put("area_comun", "Área común (m2)");
    ETIQUETAS.put("alicuota", "Alícuota");
    ETIQUETAS.put("estado_civil", "Estado civil del comprador");
    ETIQUETAS.put("monto_prestamo", "Monto del préstamo");
    ETIQUETAS.put("monto_prestamo_letras", "Monto del préstamo en letras");
    ETIQUETAS.put("plazo_credito", "Plazo del crédito");
    ETIQUETAS.put("tasa_interes_inicial", "Tasa de interés inicial");
    ETIQUETAS.put("institucion_financiera_original", "Institución financiera original");
    ETIQUETAS.put("direccion_deudor", "Dirección del deudor");
    ETIQUETAS.put("telefono_deudor", "Teléfono del deudor");
    ETIQUETAS.put("correo_deudor", "Correo del deudor");
    ETIQUETAS.put("ciudad_firma", "Ciudad de firma");
    ETIQUETAS.put("fecha_firma", "Fecha de firma");
    ETIQUETAS.put("apoderado_biess", "Apoderado especial BIESS");
    ETIQUETAS.put("cedula_apoderado_biess", "Cédula del apoderado BIESS");
    ETIQUETAS.put("nombre_vendedor", "Nombre del vendedor");
    ETIQUETAS.put("cedula_vendedor", "Cédula del vendedor");
    ETIQUETAS.put("estado_civil_vendedor", "Estado civil del vendedor");
    ETIQUETAS.put("nombre_conyuge_vendedor", "Nombre del cónyuge del vendedor");
    ETIQUETAS.put("cedula_conyuge_vendedor", "Cédula del cónyuge del vendedor");
    ETIQUETAS.put("profesion_vendedor", "Profesión del vendedor");
    ETIQUETAS.put("direccion_vendedor", "Dirección del vendedor");
    ETIQUETAS.put("telefono_vendedor", "Teléfono del vendedor");
    ETIQUETAS.put("correo_vendedor", "Correo del vendedor");
    ETIQUETAS.put("clave_catastral", "Clave catastral");
    ETIQUETAS.put("avaluo_inmueble", "Avalúo del inmueble");
    ETIQUETAS.put("precio_compraventa_numero", "Precio de compraventa (número)");
    ETIQUETAS.put("precio_compraventa_letras", "Precio de compraventa (letras)");
    ETIQUETAS.put("valor_entrada_numero", "Valor de entrada (número)");
    ETIQUETAS.put("valor_entrada_letras", "Valor de entrada (letras)");
    ETIQUETAS.put("saldo_compraventa_numero", "Saldo de compraventa (número)");
    ETIQUETAS.put("saldo_compraventa_letras", "Saldo de compraventa (letras)");
    ETIQUETAS.put("fecha_escritura_antecedente", "Fecha de la escritura antecedente");
    ETIQUETAS.put("fecha_inscripcion_antecedente", "Fecha de inscripción del antecedente");
    ETIQUETAS.put("repertorio_antecedente", "Repertorio del antecedente");
    ETIQUETAS.put("notaria_antecedente", "Notaría del antecedente");
  }

  public static String etiqueta(String campo) {
    String label = ETIQUETAS.get(campo);
    return label == null ? campo : label + " (" + campo + ")";
  }

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

  public String getAreaSolar() {
    return areaSolar;
  }

  public void setAreaSolar(String areaSolar) {
    this.areaSolar = nullToEmpty(areaSolar);
  }

  public String getAreaConstruccion() {
    return areaConstruccion;
  }

  public void setAreaConstruccion(String areaConstruccion) {
    this.areaConstruccion = nullToEmpty(areaConstruccion);
  }

  public String getAreaUtil() {
    return areaUtil;
  }

  public void setAreaUtil(String areaUtil) {
    this.areaUtil = nullToEmpty(areaUtil);
  }

  public String getAreaComun() {
    return areaComun;
  }

  public void setAreaComun(String areaComun) {
    this.areaComun = nullToEmpty(areaComun);
  }

  public String getAlicuota() {
    return alicuota;
  }

  public void setAlicuota(String alicuota) {
    this.alicuota = nullToEmpty(alicuota);
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

  public String getApoderadoBiess() {
    return apoderadoBiess;
  }

  public void setApoderadoBiess(String apoderadoBiess) {
    this.apoderadoBiess = nullToEmpty(apoderadoBiess);
  }

  public String getCedulaApoderadoBiess() {
    return cedulaApoderadoBiess;
  }

  public void setCedulaApoderadoBiess(String cedulaApoderadoBiess) {
    this.cedulaApoderadoBiess = nullToEmpty(cedulaApoderadoBiess);
  }

  public String getNombreVendedor() {
    return nombreVendedor;
  }

  public void setNombreVendedor(String nombreVendedor) {
    this.nombreVendedor = nullToEmpty(nombreVendedor);
  }

  public String getCedulaVendedor() {
    return cedulaVendedor;
  }

  public void setCedulaVendedor(String cedulaVendedor) {
    this.cedulaVendedor = nullToEmpty(cedulaVendedor);
  }

  public String getEstadoCivilVendedor() {
    return estadoCivilVendedor;
  }

  public void setEstadoCivilVendedor(String estadoCivilVendedor) {
    this.estadoCivilVendedor = nullToEmpty(estadoCivilVendedor);
  }

  public String getNombreConyugeVendedor() {
    return nombreConyugeVendedor;
  }

  public void setNombreConyugeVendedor(String nombreConyugeVendedor) {
    this.nombreConyugeVendedor = nullToEmpty(nombreConyugeVendedor);
  }

  public String getCedulaConyugeVendedor() {
    return cedulaConyugeVendedor;
  }

  public void setCedulaConyugeVendedor(String cedulaConyugeVendedor) {
    this.cedulaConyugeVendedor = nullToEmpty(cedulaConyugeVendedor);
  }

  public String getProfesionVendedor() {
    return profesionVendedor;
  }

  public void setProfesionVendedor(String profesionVendedor) {
    this.profesionVendedor = nullToEmpty(profesionVendedor);
  }

  public String getDireccionVendedor() {
    return direccionVendedor;
  }

  public void setDireccionVendedor(String direccionVendedor) {
    this.direccionVendedor = nullToEmpty(direccionVendedor);
  }

  public String getTelefonoVendedor() {
    return telefonoVendedor;
  }

  public void setTelefonoVendedor(String telefonoVendedor) {
    this.telefonoVendedor = nullToEmpty(telefonoVendedor);
  }

  public String getCorreoVendedor() {
    return correoVendedor;
  }

  public void setCorreoVendedor(String correoVendedor) {
    this.correoVendedor = nullToEmpty(correoVendedor);
  }

  public String getClaveCatastral() {
    return claveCatastral;
  }

  public void setClaveCatastral(String claveCatastral) {
    this.claveCatastral = nullToEmpty(claveCatastral);
  }

  public String getAvaluoInmueble() {
    return avaluoInmueble;
  }

  public void setAvaluoInmueble(String avaluoInmueble) {
    this.avaluoInmueble = nullToEmpty(avaluoInmueble);
  }

  public String getPrecioCompraventaNumero() {
    return precioCompraventaNumero;
  }

  public void setPrecioCompraventaNumero(String precioCompraventaNumero) {
    this.precioCompraventaNumero = nullToEmpty(precioCompraventaNumero);
  }

  public String getPrecioCompraventaLetras() {
    return precioCompraventaLetras;
  }

  public void setPrecioCompraventaLetras(String precioCompraventaLetras) {
    this.precioCompraventaLetras = nullToEmpty(precioCompraventaLetras);
  }

  public String getValorEntradaNumero() {
    return valorEntradaNumero;
  }

  public void setValorEntradaNumero(String valorEntradaNumero) {
    this.valorEntradaNumero = nullToEmpty(valorEntradaNumero);
  }

  public String getValorEntradaLetras() {
    return valorEntradaLetras;
  }

  public void setValorEntradaLetras(String valorEntradaLetras) {
    this.valorEntradaLetras = nullToEmpty(valorEntradaLetras);
  }

  public String getSaldoCompraventaNumero() {
    return saldoCompraventaNumero;
  }

  public void setSaldoCompraventaNumero(String saldoCompraventaNumero) {
    this.saldoCompraventaNumero = nullToEmpty(saldoCompraventaNumero);
  }

  public String getSaldoCompraventaLetras() {
    return saldoCompraventaLetras;
  }

  public void setSaldoCompraventaLetras(String saldoCompraventaLetras) {
    this.saldoCompraventaLetras = nullToEmpty(saldoCompraventaLetras);
  }

  public String getFechaEscrituraAntecedente() {
    return fechaEscrituraAntecedente;
  }

  public void setFechaEscrituraAntecedente(String fechaEscrituraAntecedente) {
    this.fechaEscrituraAntecedente = nullToEmpty(fechaEscrituraAntecedente);
  }

  public String getFechaInscripcionAntecedente() {
    return fechaInscripcionAntecedente;
  }

  public void setFechaInscripcionAntecedente(String fechaInscripcionAntecedente) {
    this.fechaInscripcionAntecedente = nullToEmpty(fechaInscripcionAntecedente);
  }

  public String getRepertorioAntecedente() {
    return repertorioAntecedente;
  }

  public void setRepertorioAntecedente(String repertorioAntecedente) {
    this.repertorioAntecedente = nullToEmpty(repertorioAntecedente);
  }

  public String getNotariaAntecedente() {
    return notariaAntecedente;
  }

  public void setNotariaAntecedente(String notariaAntecedente) {
    this.notariaAntecedente = nullToEmpty(notariaAntecedente);
  }

  /** {@code true} si el valor está vacío o es el marcador de dato ausente. */
  public static boolean isMissing(Object value) {
    return value == null || NODATA.equals(blankToNodata(value.toString()));
  }

  /**
   * Mapa listo para poi-tl (keys = tags del .docx). Valores vacíos → {@code nodata}. Las cifras y
   * la tasa se limpian porque las plantillas ya traen "USD" y "%"; un plazo solo numérico se
   * expresa en meses porque las plantillas no llevan la unidad.
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
    map.put("area_solar", blankToNodata(areaSolar));
    map.put("area_construccion", blankToNodata(areaConstruccion));
    map.put("area_util", blankToNodata(areaUtil));
    map.put("area_comun", blankToNodata(areaComun));
    map.put("alicuota", blankToNodata(alicuota));
    map.put("estado_civil", blankToNodata(estadoCivil));
    map.put("monto_prestamo", blankToNodata(cifra(montoPrestamo)));
    map.put("monto_prestamo_letras", blankToNodata(montoPrestamoLetras));
    map.put("plazo_credito", blankToNodata(plazo(plazoCredito)));
    map.put("tasa_interes_inicial", blankToNodata(tasa(tasaInteresInicial)));
    map.put("institucion_financiera_original", blankToNodata(institucionFinancieraOriginal));
    map.put("direccion_deudor", blankToNodata(direccionDeudor));
    map.put("telefono_deudor", blankToNodata(telefonoDeudor));
    map.put("correo_deudor", blankToNodata(correoDeudor));
    map.put("ciudad_firma", blankToNodata(ciudadFirma));
    map.put("fecha_firma", blankToNodata(fechaFirma));
    map.put("apoderado_biess", blankToNodata(apoderadoBiess));
    map.put("cedula_apoderado_biess", blankToNodata(cedulaApoderadoBiess));
    map.put("nombre_vendedor", blankToNodata(nombreVendedor));
    map.put("cedula_vendedor", blankToNodata(cedulaVendedor));
    map.put("estado_civil_vendedor", blankToNodata(estadoCivilVendedor));
    map.put("nombre_conyuge_vendedor", blankToNodata(nombreConyugeVendedor));
    map.put("cedula_conyuge_vendedor", blankToNodata(cedulaConyugeVendedor));
    map.put("profesion_vendedor", blankToNodata(profesionVendedor));
    map.put("direccion_vendedor", blankToNodata(direccionVendedor));
    map.put("telefono_vendedor", blankToNodata(telefonoVendedor));
    map.put("correo_vendedor", blankToNodata(correoVendedor));
    map.put("clave_catastral", blankToNodata(claveCatastral));
    map.put("avaluo_inmueble", blankToNodata(cifra(avaluoInmueble)));
    map.put("precio_compraventa_numero", blankToNodata(cifra(precioCompraventaNumero)));
    map.put("precio_compraventa_letras", blankToNodata(precioCompraventaLetras));
    map.put("valor_entrada_numero", blankToNodata(cifra(valorEntradaNumero)));
    map.put("valor_entrada_letras", blankToNodata(valorEntradaLetras));
    map.put("saldo_compraventa_numero", blankToNodata(cifra(saldoCompraventaNumero)));
    map.put("saldo_compraventa_letras", blankToNodata(saldoCompraventaLetras));
    map.put("fecha_escritura_antecedente", blankToNodata(fechaEscrituraAntecedente));
    map.put("fecha_inscripcion_antecedente", blankToNodata(fechaInscripcionAntecedente));
    map.put("repertorio_antecedente", blankToNodata(repertorioAntecedente));
    map.put("notaria_antecedente", blankToNodata(notariaAntecedente));
    map.put(
        "representacion_sociedad_conyugal_vendedor",
        blankToNodata(representacionSociedadConyugalVendedor));
    map.put(
        "representacion_sociedad_conyugal_comprador",
        blankToNodata(representacionSociedadConyugalComprador));
    map.put("celular_vendedor", blankToNodata(celularVendedor));
    map.put("celular_comprador", blankToNodata(celularComprador));
    map.put("canton_notario_adquisicion", blankToNodata(cantonNotarioAdquisicion));
    map.put("nombre_adquirente_anterior", blankToNodata(nombreAdquirenteAnterior));
    map.put("estado_civil_adquirente_anterior", blankToNodata(estadoCivilAdquirenteAnterior));
    map.put("descripcion_inmueble_general", blankToNodata(descripcionInmuebleGeneral));
    map.put("nombre_conjunto_edificio", blankToNodata(nombreConjuntoEdificio));
    map.put("modo_propiedad_horizontal", blankToNodata(modoPropiedadHorizontal));
    map.put("fecha_escritura_ph", blankToNodata(fechaEscrituraPh));
    map.put("notario_ph", blankToNodata(notarioPh));
    map.put("canton_notario_ph", blankToNodata(cantonNotarioPh));
    map.put("canton_registro_ph", blankToNodata(cantonRegistroPh));
    map.put("fecha_inscripcion_ph", blankToNodata(fechaInscripcionPh));
    map.put("fecha_escritura_antecedente_tres", blankToNodata(fechaEscrituraAntecedenteTres));
    map.put("notario_antecedente_tres", blankToNodata(notarioAntecedenteTres));
    map.put("canton_notario_antecedente_tres", blankToNodata(cantonNotarioAntecedenteTres));
    map.put("canton_registro_antecedente_tres", blankToNodata(cantonRegistroAntecedenteTres));
    map.put("fecha_inscripcion_antecedente_tres", blankToNodata(fechaInscripcionAntecedenteTres));
    map.put("linderos_generales_norte", blankToNodata(linderosGeneralesNorte));
    map.put("linderos_generales_sur", blankToNodata(linderosGeneralesSur));
    map.put("linderos_generales_este", blankToNodata(linderosGeneralesEste));
    map.put("linderos_generales_oeste", blankToNodata(linderosGeneralesOeste));
    map.put("superficie_general", blankToNodata(superficieGeneral));
    map.put(
        "detalles_linderos_especificos_completos",
        blankToNodata(detallesLinderosEspecificosCompletos));
    map.put("detalle_forma_pago", blankToNodata(detalleFormaPago));
    map.put("art_y_deudor_2", blankToNodata(artYDeudor2));
    map.put("calidad_afiliado", blankToNodata(calidadAfiliado));
    return map;
  }

  private static final String NODATA = "nodata";

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNodata(String value) {
    String t = nullToEmpty(value);
    if (t.isEmpty()
        || NODATA.equalsIgnoreCase(t)
        || "null".equalsIgnoreCase(t)
        || "n/a".equalsIgnoreCase(t)) {
      return NODATA;
    }
    return t;
  }

  private static String cifra(String value) {
    return nullToEmpty(value).replaceFirst("(?i)^(USD|US\\$|\\$)\\s*(\\$\\s*)?", "").trim();
  }

  private static String tasa(String value) {
    return nullToEmpty(value).replaceFirst("\\s*%+$", "");
  }

  private static String plazo(String value) {
    String t = nullToEmpty(value);
    return t.matches("\\d+") ? t + " meses" : t;
  }
}
