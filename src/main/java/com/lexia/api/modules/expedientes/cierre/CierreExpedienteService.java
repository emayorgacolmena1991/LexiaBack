package com.lexia.api.modules.expedientes.cierre;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.cierre.CartaFinanciamiento.Pagador;
import com.lexia.api.modules.expedientes.cierre.CartaFinanciamiento.Pagos;
import com.lexia.api.modules.expedientes.comparecientes.Compareciente;
import com.lexia.api.modules.expedientes.comparecientes.ComparecientesService;
import com.lexia.api.modules.expedientes.comparecientes.RolCompareciente;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionesDocumentoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.ObservacionesPorDocumento;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateCatalog;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.expedientes.minutas.NumeroALetras;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.expedientes.requisitos.CondicionCaso;
import com.lexia.api.modules.expedientes.requisitos.ExpedienteCondicion;
import com.lexia.api.modules.expedientes.requisitos.ExpedienteCondicionRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import jakarta.validation.constraints.Size;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Documentos de cierre del expediente (fase 6): carta de financiamiento, informe legal final y
 * correo de observaciones al afiliado, más los datos de cierre que ingresa el abogado. Solo
 * expedientes oficiales del tenant de la sesión (otro tenant o borrador sin promover → 404). El
 * permiso lo comprueba el controlador.
 */
@Service
public class CierreExpedienteService {

  private static final Logger LOG = LoggerFactory.getLogger(CierreExpedienteService.class);
  private static final ZoneId ZONA = ZoneId.of("America/Guayaquil");
  private static final Locale ES = Locale.forLanguageTag("es");
  private static final Pattern CODIGO_INICIAL =
      Pattern.compile("^\\[?[A-Z]{2,}[A-Z0-9_.\\-]*\\]?\\s*[:\\-–]\\s*");
  private static final String CONTRATO_BASE =
      "HIPOTECA ABIERTA Y PROHIBICIÓN VOLUNTARIA DE ENAJENAR Y GRAVAR";

  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final ExpedienteCondicionRepository condiciones;
  private final ObjectProvider<ComparecientesService> comparecientes;
  private final MinutaGenerationService minutas;
  private final ProductoBiessService productos;
  private final DocxMinutaRenderer renderer;
  private final ObservacionesPorDocumento observaciones;
  private final BorradorPromocionService promocion;
  private final ObjectProvider<TenantParameterService> parametros;

  public CierreExpedienteService(
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      ExpedienteCondicionRepository condiciones,
      ObjectProvider<ComparecientesService> comparecientes,
      MinutaGenerationService minutas,
      ProductoBiessService productos,
      DocxMinutaRenderer renderer,
      ObservacionesPorDocumento observaciones,
      BorradorPromocionService promocion,
      ObjectProvider<TenantParameterService> parametros) {
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.condiciones = condiciones;
    this.comparecientes = comparecientes;
    this.minutas = minutas;
    this.productos = productos;
    this.renderer = renderer;
    this.observaciones = observaciones;
    this.promocion = promocion;
    this.parametros = parametros;
  }

  /**
   * @param cartaDisponible FINANCIAMIENTO_GASTOS confirmada.
   * @param faltantesInforme vacío = el informe legal se puede generar.
   */
  public record DatosCierreDTO(
      UUID caseId,
      LocalDate fechaEscritura,
      String notaria,
      LocalDate fechaInscripcion,
      LocalDate fechaInforme,
      String pagoPlusvalia,
      String pagoAlcabalas,
      String pagoObras,
      boolean cartaDisponible,
      List<String> faltantesCarta,
      List<String> faltantesInforme,
      List<String> avisosInforme) {}

  /** Lo que necesita el paso 5: sin armar el informe. */
  public record EstadoCartaDTO(
      UUID caseId,
      boolean cartaDisponible,
      String pagoPlusvalia,
      String pagoAlcabalas,
      String pagoObras,
      List<String> faltantesCarta) {}

  public record DatosCierreRequest(
      LocalDate fechaEscritura,
      @Size(max = 200) String notaria,
      LocalDate fechaInscripcion,
      LocalDate fechaInforme) {}

  /** VENDEDOR, CLIENTE, FINANCIADO o vacío (sin marcar). */
  public record PagosCartaRequest(
      @Size(max = 12) String plusvalia, @Size(max = 12) String alcabalas, @Size(max = 12) String obras) {}

  public record CorreoAfiliadoDTO(String asunto, String texto, int observaciones) {}

  public record Archivo(String nombre, byte[] bytes) {}

  // ---------------------------------------------------------------- datos de cierre

  @Transactional(readOnly = true)
  public DatosCierreDTO obtener(UUID id) {
    UUID tenantId = tenant();
    return dto(requireCase(id, tenantId), tenantId);
  }

  @Transactional
  public DatosCierreDTO guardar(UUID id, DatosCierreRequest body) {
    UUID tenantId = tenant();
    LegalCase legalCase = requireCase(id, tenantId);
    DatosCierreRequest b = body == null ? new DatosCierreRequest(null, null, null, null) : body;
    legalCase.setDatosCierre(
        b.fechaEscritura(), blankToNull(b.notaria()), b.fechaInscripcion(), b.fechaInforme());
    legalCases.save(legalCase);
    LOG.info("Datos de cierre guardados case={}", legalCase.getId());
    return dto(legalCase, tenantId);
  }

  @Transactional(readOnly = true)
  public EstadoCartaDTO estadoCarta(UUID id) {
    UUID tenantId = tenant();
    return estado(requireCase(id, tenantId), tenantId);
  }

  @Transactional
  public EstadoCartaDTO guardarPagos(UUID id, PagosCartaRequest body) {
    UUID tenantId = tenant();
    LegalCase legalCase = requireCase(id, tenantId);
    PagosCartaRequest b = body == null ? new PagosCartaRequest(null, null, null) : body;
    legalCase.setPagosCarta(pagador(b.plusvalia()), pagador(b.alcabalas()), pagador(b.obras()));
    legalCases.save(legalCase);
    return estado(legalCase, tenantId);
  }

  private EstadoCartaDTO estado(LegalCase legalCase, UUID tenantId) {
    boolean disponible =
        condicionConfirmada(tenantId, legalCase.getId(), CondicionCaso.FINANCIAMIENTO_GASTOS);
    List<String> faltantes =
        disponible
            ? CartaFinanciamiento.faltantes(compradores(legalCase, tenantId), legalCase.getNumeroInstancia())
            : List.of();
    return new EstadoCartaDTO(
        legalCase.getId(),
        disponible,
        legalCase.getCartaPagoPlusvalia(),
        legalCase.getCartaPagoAlcabalas(),
        legalCase.getCartaPagoObras(),
        faltantes);
  }

  /** Compradores de la lista; la minuta (consulta pesada) solo si la lista no los tiene. */
  private List<PartesCierre.Persona> compradores(LegalCase legalCase, UUID tenantId) {
    List<Compareciente> filas = filas(tenantId, legalCase.getId());
    List<PartesCierre.Persona> deLista = PartesCierre.de(filas, null).compradores();
    return !deLista.isEmpty()
        ? deLista
        : PartesCierre.de(filas, minutas.datosConsolidados(tenantId, legalCase.getId())).compradores();
  }

  // ---------------------------------------------------------------- carta de financiamiento

  @Transactional(readOnly = true)
  public Archivo carta(UUID id) {
    return carta(id, null);
  }

  /** {@code fecha}: la que elige el abogado; null = hoy. */
  @Transactional(readOnly = true)
  public Archivo carta(UUID id, LocalDate fecha) {
    UUID tenantId = tenant();
    LegalCase legalCase = requireCase(id, tenantId);
    if (!condicionConfirmada(tenantId, legalCase.getId(), CondicionCaso.FINANCIAMIENTO_GASTOS)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CARTA_SIN_FINANCIAMIENTO",
          "La carta de financiamiento solo aplica cuando el estudio financia los gastos (condición"
              + " confirmada).");
    }
    PartesCierre.Partes partes = partes(legalCase, tenantId);
    List<PartesCierre.Persona> vendedores = partes.vendedores();
    List<PartesCierre.Persona> compradores = partes.compradores();
    List<String> faltantes = CartaFinanciamiento.faltantes(compradores, legalCase.getNumeroInstancia());
    if (!faltantes.isEmpty()) {
      throw faltan("CARTA_INCOMPLETA", "Faltan datos para la carta de financiamiento.", faltantes);
    }
    Pagos pagos =
        new Pagos(
            Pagador.de(legalCase.getCartaPagoPlusvalia()).orElse(null),
            Pagador.de(legalCase.getCartaPagoAlcabalas()).orElse(null),
            Pagador.de(legalCase.getCartaPagoObras()).orElse(null));
    Map<String, Object> datos =
        CartaFinanciamiento.datos(
            vendedores,
            compradores,
            legalCase.getNumeroInstancia(),
            fecha == null ? LocalDate.now(ZONA) : fecha,
            pagos);
    byte[] docx = renderer.renderPlantilla(CartaFinanciamiento.RECURSO, datos);
    LOG.info(
        "Carta de financiamiento case={} vendedores={} compradores={} bytes={}",
        legalCase.getId(),
        vendedores.size(),
        compradores.size(),
        docx.length);
    return new Archivo("carta_financiamiento_" + legalCase.getCode() + ".docx", docx);
  }

  // ---------------------------------------------------------------- informe legal final

  @Transactional(readOnly = true)
  public Archivo informe(UUID id) {
    UUID tenantId = tenant();
    LegalCase legalCase = requireCase(id, tenantId);
    InformeLegal.Datos datos =
        datosInforme(legalCase, tenantId, minutas.datosConsolidados(tenantId, legalCase.getId()));
    List<String> faltantes = InformeLegal.faltantes(datos);
    if (!faltantes.isEmpty()) {
      throw faltan("INFORME_INCOMPLETO", "Faltan datos para el informe legal.", faltantes);
    }
    byte[] xlsx = InformeLegal.escribir(datos);
    LOG.info("Informe legal case={} bytes={}", legalCase.getId(), xlsx.length);
    return new Archivo("informe_legal_" + legalCase.getCode() + ".xlsx", xlsx);
  }

  private PartesCierre.Partes partes(LegalCase legalCase, UUID tenantId) {
    return PartesCierre.de(
        filas(tenantId, legalCase.getId()), minutas.datosConsolidados(tenantId, legalCase.getId()));
  }

  InformeLegal.Datos datosInforme(LegalCase legalCase, UUID tenantId, MinutaViviendaData d) {
    UUID caseId = legalCase.getId();
    List<Compareciente> filas = filas(tenantId, caseId);
    PartesCierre.Partes partes = PartesCierre.de(filas, d);
    String producto = producto(legalCase, tenantId);
    boolean sustitucion =
        MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA.equalsIgnoreCase(codigoProducto(legalCase, tenantId));
    String bruta = limpio(d.getDescripcionInmuebleHipoteca());
    if (bruta.isEmpty()) {
      bruta = limpio(d.getDescripcionInmueblesAntecedentes());
    }
    InformeLegal.Descripcion descripcion = InformeLegal.sinLinderos(bruta);
    return new InformeLegal.Datos(
        limpio(legalCase.getNumeroInstancia()),
        PartesCierre.nombres(partes.compradores()),
        tipoContrato(legalCase, tenantId, producto),
        descripcion.texto(),
        limpio(d.getParroquiaInmueble()).toUpperCase(ES),
        limpio(d.getCantonInmueble()).toUpperCase(ES),
        limpio(d.getProvinciaInmueble()).toUpperCase(ES),
        monto(limpio(d.getMontoPrestamo())),
        limpio(legalCase.getCierreNotaria()),
        CartaFinanciamiento.fechaLarga(legalCase.getCierreFechaEscritura()),
        CartaFinanciamiento.fechaLarga(legalCase.getCierreFechaInscripcion()),
        PartesCierre.nombres(partes.vendedores()),
        observacionesApoderados(filas),
        parametro(tenantId, TenantParameterService.KEY_FIRMA_CIUDAD, TenantParameterService.CIUDAD_POR_DEFECTO),
        CartaFinanciamiento.fechaLarga(legalCase.getCierreFechaInforme()),
        parametro(tenantId, TenantParameterService.KEY_FIRMA_ABOGADO, ""),
        parametro(tenantId, TenantParameterService.KEY_FIRMA_REGISTRO, ""),
        !sustitucion,
        descripcion.revisar() ? List.of(InformeLegal.AVISO_DESCRIPCION) : List.of());
  }

  /**
   * «COMPRAVENTA E HIPOTECA ABIERTA… – PRODUCTO»; con entrega de obra o cancelación de hipoteca,
   * «CANCELACIÓN DE HIPOTECA, COMPRAVENTA CON ENTREGA DE OBRA E HIPOTECA ABIERTA… – PRODUCTO».
   */
  String tipoContrato(LegalCase legalCase, UUID tenantId, String producto) {
    String codigo = codigoProducto(legalCase, tenantId);
    if (!StringUtils.hasText(producto)) {
      return "";
    }
    boolean entregaObra = condicionConfirmada(tenantId, legalCase.getId(), CondicionCaso.ENTREGA_OBRA);
    return tipoContrato(codigo, producto, entregaObra);
  }

  static String tipoContrato(String codigoProducto, String producto, boolean entregaObra) {
    String sufijo = " – " + producto.trim().toUpperCase(ES);
    if (MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA.equalsIgnoreCase(codigoProducto)) {
      return "CANCELACIÓN DE HIPOTECA E " + CONTRATO_BASE + sufijo;
    }
    boolean cancelacion =
        MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS.equalsIgnoreCase(codigoProducto);
    return (cancelacion ? "CANCELACIÓN DE HIPOTECA, " : "")
        + "COMPRAVENTA"
        + (entregaObra ? " CON ENTREGA DE OBRA" : "")
        + " E "
        + CONTRATO_BASE
        + sufijo;
  }

  /** «Comparecen mediante apoderado: A, B. Apoderado(s): C.» (vacío si nadie). */
  static String observacionesApoderados(List<Compareciente> filas) {
    List<String> representados = new ArrayList<>();
    List<String> apoderados = new ArrayList<>();
    for (Compareciente c : filas) {
      if (c.rolEnum() == RolCompareciente.APODERADO) {
        apoderados.add(nombre(c));
      } else if (c.isConApoderado()) {
        representados.add(nombre(c));
      }
    }
    if (representados.isEmpty() && apoderados.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (!representados.isEmpty()) {
      sb.append(representados.size() == 1 ? "Comparece mediante apoderado: " : "Comparecen mediante apoderado: ")
          .append(CartaFinanciamiento.unir(representados))
          .append(".");
    }
    if (!apoderados.isEmpty()) {
      sb.append(sb.isEmpty() ? "" : " ")
          .append(apoderados.size() == 1 ? "Apoderado: " : "Apoderados: ")
          .append(CartaFinanciamiento.unir(apoderados))
          .append(".");
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- correo al afiliado

  @Transactional(readOnly = true)
  public CorreoAfiliadoDTO correo(UUID id) {
    UUID tenantId = tenant();
    LegalCase legalCase = requireCase(id, tenantId);
    WritingFile file =
        writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(legalCase.getId(), tenantId).orElse(null);
    ObservacionesPorDocumento.Agrupadas agrupadas =
        observaciones.abiertas(legalCase.getId(), file == null ? null : file.getId(), tenantId);
    Map<String, String> tipos = new HashMap<>();
    observaciones
        .tiposDelProducto(legalCase.getId().toString())
        .forEach(t -> tipos.put(t.codigo(), t.nombre()));

    List<CorreoAfiliado.Grupo> grupos = new ArrayList<>();
    for (ObservacionesDocumentoItem doc : agrupadas.porDocumento()) {
      grupos.add(new CorreoAfiliado.Grupo(doc.nombre(), lineas(doc.observaciones(), Map.of())));
    }
    grupos.add(new CorreoAfiliado.Grupo("Documentos faltantes", lineas(agrupadas.faltantes(), tipos)));
    grupos.add(new CorreoAfiliado.Grupo("Otras observaciones", lineas(agrupadas.generales(), Map.of())));

    String producto = producto(legalCase, tenantId).toUpperCase(ES);
    CorreoAfiliado.Firma firma =
        new CorreoAfiliado.Firma(
            parametro(tenantId, TenantParameterService.KEY_FIRMA_ESTUDIO, ""),
            parametro(tenantId, TenantParameterService.KEY_FIRMA_ABOGADO, ""),
            parametro(tenantId, TenantParameterService.KEY_FIRMA_TELEFONO, ""),
            parametro(tenantId, TenantParameterService.KEY_FIRMA_CORREO, ""));
    String texto =
        CorreoAfiliado.texto(
            producto,
            grupos,
            firma,
            parametro(tenantId, TenantParameterService.KEY_CORREO_AFILIADO_PLANTILLA, ""));
    LOG.info(
        "Correo al afiliado case={} observaciones={} chars={}",
        legalCase.getId(),
        agrupadas.abiertas().size(),
        texto.length());
    return new CorreoAfiliadoDTO(
        CorreoAfiliado.asunto(producto, legalCase.getNumeroInstancia()),
        texto,
        agrupadas.abiertas().size());
  }

  /** Mensajes sin códigos internos; los faltantes llevan el nombre del documento que falta. */
  static List<String> lineas(List<ObservacionCotejoItem> items, Map<String, String> tipos) {
    List<String> out = new ArrayList<>();
    for (ObservacionCotejoItem o : items) {
      String mensaje = simple(o.mensaje());
      String tipo =
          o.tipoDocumentoFaltante() == null ? null : tipos.getOrDefault(o.tipoDocumentoFaltante(), null);
      String linea;
      if (StringUtils.hasText(tipo) && !mensaje.toLowerCase(ES).contains(tipo.toLowerCase(ES))) {
        linea = mensaje.isEmpty() ? tipo : tipo + ": " + mensaje;
      } else {
        linea = mensaje;
      }
      if (!linea.isEmpty() && !out.contains(linea)) {
        out.add(linea);
      }
    }
    return out;
  }

  static String simple(String mensaje) {
    if (mensaje == null) {
      return "";
    }
    String s = CODIGO_INICIAL.matcher(mensaje.trim()).replaceFirst("").trim();
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  // ---------------------------------------------------------------- apoyo

  private DatosCierreDTO dto(LegalCase legalCase, UUID tenantId) {
    MinutaViviendaData minuta = minutas.datosConsolidados(tenantId, legalCase.getId());
    InformeLegal.Datos informe = datosInforme(legalCase, tenantId, minuta);
    return new DatosCierreDTO(
        legalCase.getId(),
        legalCase.getCierreFechaEscritura(),
        legalCase.getCierreNotaria(),
        legalCase.getCierreFechaInscripcion(),
        legalCase.getCierreFechaInforme(),
        legalCase.getCartaPagoPlusvalia(),
        legalCase.getCartaPagoAlcabalas(),
        legalCase.getCartaPagoObras(),
        condicionConfirmada(tenantId, legalCase.getId(), CondicionCaso.FINANCIAMIENTO_GASTOS),
        CartaFinanciamiento.faltantes(
            PartesCierre.de(filas(tenantId, legalCase.getId()), minuta).compradores(),
            legalCase.getNumeroInstancia()),
        InformeLegal.faltantes(informe),
        informe.avisos());
  }

  private LegalCase requireCase(UUID id, UUID tenantId) {
    UUID caseId =
        promocion.expedienteOficial(id).orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
    return legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
  }

  private boolean condicionConfirmada(UUID tenantId, UUID caseId, CondicionCaso condicion) {
    for (ExpedienteCondicion c : condiciones.findByTenantIdAndExpedienteId(tenantId, caseId)) {
      if (condicion.name().equals(c.getCondicion()) && c.isConfirmada()) {
        return true;
      }
    }
    return false;
  }

  private List<Compareciente> filas(UUID tenantId, UUID caseId) {
    ComparecientesService service = comparecientes.getIfAvailable();
    return service == null ? List.of() : service.filas(tenantId, caseId);
  }

  private String codigoProducto(LegalCase legalCase, UUID tenantId) {
    return writingFiles
        .findByCaseIdAndTenantIdAndDeletedAtIsNull(legalCase.getId(), tenantId)
        .map(WritingFile::getProductCode)
        .filter(StringUtils::hasText)
        .orElse(legalCase.getProductCode() == null ? "" : legalCase.getProductCode())
        .trim();
  }

  /** Nombre visible del producto (tipo y subtipo); el código si el catálogo no lo tiene. */
  private String producto(LegalCase legalCase, UUID tenantId) {
    String codigo = codigoProducto(legalCase, tenantId);
    if (codigo.isEmpty()) {
      return "";
    }
    return productos.etiquetas(tenantId).getOrDefault(codigo, codigo.replace('_', ' '));
  }

  private String parametro(UUID tenantId, String key, String porDefecto) {
    TenantParameterService service = parametros.getIfAvailable();
    String valor = service == null ? porDefecto : service.getValue(tenantId, key, porDefecto);
    return valor == null ? "" : valor.trim();
  }

  private static String nombre(Compareciente c) {
    return c.getNombres() == null ? "" : c.getNombres().trim().toUpperCase(ES);
  }

  private static String limpio(String valor) {
    return MinutaViviendaData.isMissing(valor) ? "" : valor.trim();
  }

  /**
   * «85.000,00» con el mismo parseo de la minuta. Vacío si no se puede interpretar: la celda no se
   * escribe y la cuantía queda en faltantes.
   */
  static String monto(String valor) {
    return NumeroALetras.parsearMonto(valor)
        .map(
            n ->
                new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-EC")))
                    .format(n))
        .orElse("");
  }

  private static String pagador(String codigo) {
    if (!StringUtils.hasText(codigo)) {
      return null;
    }
    return Pagador.de(codigo)
        .map(Enum::name)
        .orElseThrow(() -> ApiException.badRequest("Pagador no válido: use VENDEDOR, CLIENTE o FINANCIADO."));
  }

  private static ApiException faltan(String code, String mensaje, List<String> faltantes) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY, code, mensaje, List.of(), Map.of("faltantes", List.copyOf(faltantes)));
  }

  private static String blankToNull(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  private static UUID tenant() {
    return AuthContext.require().tenantId();
  }
}
