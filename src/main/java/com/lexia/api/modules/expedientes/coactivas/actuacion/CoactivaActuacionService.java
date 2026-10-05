package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ActuacionResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.HonorariosResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PlantillaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ResultadoHttp;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.SolicitudResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage.StoredFile;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.minutas.DocxMinutaRenderer;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaActuacionService {

  static final String FIRMA_NO_CONFIGURADA =
      "No hay proveedor de firma electrónica configurado. La actuación sigue en estado GENERADO.";
  static final String DATADOC_NO_CONFIGURADO =
      "DataDoc no está configurado en este ambiente. No se sincronizó ningún dato.";

  static final String PLANTILLA_DATOS_INCOMPLETOS = "PLANTILLA_DATOS_INCOMPLETOS";
  private static final String PREFIJO_PLANTILLAS = "templates/coactivas/";

  private static final Set<String> OPI = Set.of("OPI_EMITIDA", "NOTIFICACION_COA");

  private final AuthorizationService authorization;
  private final CoactivaExpedienteService expedientes;
  private final CoactivaPlantillaRepository plantillas;
  private final CoactivaActuacionRepository actuaciones;
  private final CoactivaMedidaRepository medidas;
  private final CoactivaSolicitudRepository solicitudes;
  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final CoactivaPlantillaDataMapper mapper;
  private final DocxMinutaRenderer renderer;
  private final DocxPdfConverter pdfConverter;

  public CoactivaActuacionService(
      AuthorizationService authorization,
      CoactivaExpedienteService expedientes,
      CoactivaPlantillaRepository plantillas,
      CoactivaActuacionRepository actuaciones,
      CoactivaMedidaRepository medidas,
      CoactivaSolicitudRepository solicitudes,
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      CoactivaPlantillaDataMapper mapper,
      DocxMinutaRenderer renderer,
      DocxPdfConverter pdfConverter) {
    this.authorization = authorization;
    this.expedientes = expedientes;
    this.plantillas = plantillas;
    this.actuaciones = actuaciones;
    this.medidas = medidas;
    this.solicitudes = solicitudes;
    this.archivos = archivos;
    this.storage = storage;
    this.mapper = mapper;
    this.renderer = renderer;
    this.pdfConverter = pdfConverter;
  }

  @Transactional(readOnly = true)
  public List<PlantillaItem> plantillas(UUID expedienteId, String etapa) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    AuthPrincipal principal = AuthContext.require();
    expedientes.require(principal.tenantId(), expedienteId);
    CoactivaEtapa parsed =
        CoactivaEtapa.parse(etapa).orElseThrow(() -> ApiException.badRequest("Etapa procesal desconocida."));
    Set<String> etapas = OPI.contains(parsed.name()) ? OPI : Set.of(parsed.name());
    return plantillas
        .findByTenantIdAndEtapaInAndActivoTrueOrderByNombreAsc(principal.tenantId(), etapas)
        .stream()
        .map(p -> new PlantillaItem(p.getId(), p.getNombre(), p.getEtapa(), p.generable()))
        .toList();
  }

  public record GenerarResultado(boolean creada, ActuacionResponse body) {}

  @Transactional
  public GenerarResultado generar(
      UUID expedienteId, UUID plantillaId, String idempotencyKey, boolean permitirIncompleto) {
    authorization.requirePermission(CoactivaPermisos.GENERAR_DOCUMENTOS);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    String clave = normalizarClave(idempotencyKey);
    if (clave != null) {
      var previa =
          actuaciones.findByTenantIdAndExpedienteIdAndIdempotencyKey(tenantId, expedienteId, clave);
      if (previa.isPresent()) {
        CoactivaActuacion existente = previa.get();
        if (!existente.getPlantillaId().equals(plantillaId)) {
          throw new ApiException(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_CONFLICT",
              "Esa Idempotency-Key ya se usó con otra plantilla.");
        }
        return new GenerarResultado(
            false, new ActuacionResponse(existente.getId(), existente.getArchivoId(), existente.getEstado()));
      }
    }
    if (expediente.getEtapaVerificada() == null) {
      throw ApiException.badRequest("Confirma la etapa del expediente antes de generar la actuación.");
    }
    CoactivaPlantilla plantilla =
        plantillas
            .findByIdAndTenantIdAndActivoTrue(plantillaId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Plantilla no encontrada."));
    if (!compatible(plantilla.getEtapa(), expediente.getEtapaVerificada())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "PLANTILLA_ETAPA",
          "La plantilla no corresponde a la etapa verificada del expediente.");
    }
    String ruta = rutaDocx(plantilla);
    PlantillaDatos datos = mapper.mapear(expediente);
    List<String> faltantes = datos.faltantes(renderer.tags(ruta));
    if (!faltantes.isEmpty() && !permitirIncompleto) {
      throw datosIncompletos(faltantes, datos.analisisDisponible());
    }
    byte[] pdf = pdfConverter.toPdf(renderer.renderPlantilla(ruta, datos.valoresConVacios(faltantes)));
    String nombre = nombrePdf(plantilla.getNombre());
    UUID archivoId = UUID.randomUUID();
    StoredFile stored = storage.storeBytes(tenantId, archivoId, nombre, pdf);
    CoactivaArchivo archivo =
        CoactivaArchivo.create(
            archivoId,
            tenantId,
            "ACTUACION",
            nombre,
            "application/pdf",
            stored.size(),
            stored.sha256(),
            stored.path(),
            principal.userId());
    archivo.vincularExpediente(expedienteId);
    archivos.save(archivo);
    CoactivaActuacion actuacion =
        actuaciones.save(
            CoactivaActuacion.crear(
                tenantId, expedienteId, plantillaId, archivoId, clave, principal.userId()));
    expedientes.registrarEvento(
        tenantId,
        expedienteId,
        "ACTUACION",
        "Actuación generada: " + plantilla.getNombre(),
        "Estado GENERADO. Usuario "
            + principal.userId()
            + (datos.discrepancias().isEmpty()
                ? ""
                : ". Se usó el dato del expediente donde la IA leyó otro valor: "
                    + String.join(", ", datos.discrepancias()))
            + (faltantes.isEmpty()
                ? ""
                : ". Generado con datos incompletos confirmado por el usuario; campos vacíos: "
                    + String.join(", ", faltantes)),
        principal.userId(),
        archivoId);
    expediente.setFechaUltimaActuacion(LocalDate.now());
    expediente.touch(principal.userId());
    return new GenerarResultado(
        true, new ActuacionResponse(actuacion.getId(), archivoId, actuacion.getEstado()));
  }

  @Transactional
  public ResultadoHttp firmar(UUID actuacionId, String idempotencyKey) {
    authorization.requirePermission(CoactivaPermisos.FIRMAR_DOCUMENTOS);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaActuacion actuacion =
        actuaciones
            .findByIdAndTenantId(actuacionId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Actuación no encontrada."));
    if (!CoactivaActuacion.GENERADO.equals(actuacion.getEstado())) {
      throw new ApiException(
          HttpStatus.CONFLICT, "FIRMA_ESTADO", "Solo se puede intentar la firma de una actuación generada.");
    }
    String detalle = "actuacion:" + actuacionId;
    return registrarIntento(
        principal,
        actuacion.getExpedienteId(),
        "FIRMA",
        detalle,
        idempotencyKey,
        422,
        "FIRMA_PROVEEDOR_NO_CONFIGURADO",
        FIRMA_NO_CONFIGURADA,
        "Firma no aplicada",
        FIRMA_NO_CONFIGURADA,
        actuacion.getArchivoId());
  }

  @Transactional(readOnly = true)
  public List<MedidaItem> medidas(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    AuthPrincipal principal = AuthContext.require();
    expedientes.require(principal.tenantId(), expedienteId);
    return medidas
        .findByTenantIdAndExpedienteIdOrderByCreatedAtDesc(principal.tenantId(), expedienteId)
        .stream()
        .map(m -> new MedidaItem(m.getId(), m.getTipo(), m.getDescripcion(), m.getFecha(), m.getEstado(), m.getCreatedAt()))
        .toList();
  }

  @Transactional
  public MedidaItem crearMedida(UUID expedienteId, MedidaRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    if (!CoactivaEtapa.EMBARGO.name().equals(expediente.getEtapaVerificada())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "MEDIDA_ETAPA",
          "Las medidas cautelares se registran cuando la etapa verificada es embargo.");
    }
    CoactivaMedida medida =
        medidas.save(
            CoactivaMedida.crear(
                tenantId,
                expedienteId,
                request.tipo().trim(),
                CoactivaTexto.blankToNull(request.descripcion()),
                request.fecha(),
                principal.userId()));
    expedientes.registrarEvento(
        tenantId,
        expedienteId,
        "MEDIDA",
        "Medida cautelar: " + medida.getTipo(),
        medida.getDescripcion(),
        principal.userId());
    expediente.touch(principal.userId());
    return new MedidaItem(
        medida.getId(), medida.getTipo(), medida.getDescripcion(), medida.getFecha(), medida.getEstado(), medida.getCreatedAt());
  }

  @Transactional(readOnly = true)
  public HonorariosResponse honorarios(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    AuthPrincipal principal = AuthContext.require();
    CoactivaExpediente expediente = expedientes.require(principal.tenantId(), expedienteId);
    return new HonorariosResponse(
        false,
        "No hay regla de honorarios configurada. El backend no calcula un monto.",
        expediente.getMontoOriginal(),
        null,
        null,
        null);
  }

  @Transactional
  public SolicitudResponse solicitarCarga(UUID expedienteId, String nota, String idempotencyKey) {
    authorization.requirePermission(CoactivaPermisos.FINANCIERO);
    AuthPrincipal principal = AuthContext.require();
    expedientes.require(principal.tenantId(), expedienteId);
    String detalle = CoactivaTexto.blankToNull(nota) == null ? "carga" : CoactivaTexto.truncate(nota.trim(), 600);
    ResultadoHttp resultado =
        registrarIntento(
            principal,
            expedienteId,
            "CARGA",
            detalle,
            idempotencyKey,
            201,
            "SOLICITUD_REGISTRADA",
            "Solicitud de carga registrada.",
            "Solicitud de carga",
            detalle,
            null);
    return new SolicitudResponse(resultado.id(), "REGISTRADO");
  }

  @Transactional
  public SolicitudResponse correrTraslado(UUID expedienteId, String idempotencyKey) {
    authorization.requirePermission(CoactivaPermisos.CONFIRMAR_DIAGNOSTICO);
    AuthPrincipal principal = AuthContext.require();
    expedientes.require(principal.tenantId(), expedienteId);
    ResultadoHttp resultado =
        registrarIntento(
            principal,
            expedienteId,
            "TRASLADO",
            "traslado",
            idempotencyKey,
            201,
            "TRASLADO_REGISTRADO",
            "Traslado registrado.",
            "Traslado corrido",
            "Registrado por " + principal.userId(),
            null);
    return new SolicitudResponse(resultado.id(), "REGISTRADO");
  }

  @Transactional
  public ResultadoHttp sincronizarDataDoc(UUID expedienteId, String idempotencyKey) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    expedientes.require(principal.tenantId(), expedienteId);
    return registrarIntento(
        principal,
        expedienteId,
        "DATADOC",
        "sync",
        idempotencyKey,
        502,
        "DATADOC_NO_CONFIGURADO",
        DATADOC_NO_CONFIGURADO,
        "DataDoc no sincronizado",
        DATADOC_NO_CONFIGURADO,
        null);
  }

  private ResultadoHttp registrarIntento(
      AuthPrincipal principal,
      UUID expedienteId,
      String operacion,
      String detalle,
      String idempotencyKey,
      int http,
      String code,
      String message,
      String tituloEvento,
      String detalleEvento,
      UUID archivoId) {
    UUID tenantId = principal.tenantId();
    String clave = normalizarClave(idempotencyKey);
    if (clave != null) {
      var previa =
          solicitudes.findByTenantIdAndExpedienteIdAndOperacionAndIdempotencyKey(
              tenantId, expedienteId, operacion, clave);
      if (previa.isPresent()) {
        CoactivaSolicitud existente = previa.get();
        if (!Objects.equals(existente.getDetalle(), detalle)) {
          throw new ApiException(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_CONFLICT",
              "Esa Idempotency-Key ya se usó con otros datos.");
        }
        return new ResultadoHttp(
            existente.getResultadoHttp(),
            existente.getResultadoCode(),
            existente.getResultadoMessage(),
            existente.getId());
      }
    }
    CoactivaSolicitud solicitud =
        solicitudes.save(
            CoactivaSolicitud.crear(
                tenantId,
                expedienteId,
                operacion,
                detalle,
                clave,
                http,
                code,
                message,
                principal.userId()));
    expedientes.registrarEvento(
        tenantId, expedienteId, operacion, tituloEvento, detalleEvento, principal.userId(), archivoId);
    return new ResultadoHttp(http, code, message, solicitud.getId());
  }

  private static String normalizarClave(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String clave = raw.trim();
    if (clave.length() > 120) {
      throw ApiException.badRequest("Idempotency-Key supera 120 caracteres.");
    }
    return clave;
  }

  private static boolean compatible(String plantillaEtapa, String etapaVerificada) {
    if (plantillaEtapa.equals(etapaVerificada)) {
      return true;
    }
    return OPI.contains(plantillaEtapa) && OPI.contains(etapaVerificada);
  }

  private static String rutaDocx(CoactivaPlantilla plantilla) {
    if (!plantilla.generable()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PLANTILLA_SIN_DOCX",
          "El formato " + plantilla.getNombre() + " todavía no tiene una plantilla .docx asociada.");
    }
    String ruta = plantilla.getRutaDocx().trim();
    if (!ruta.startsWith(PREFIJO_PLANTILLAS) || !ruta.endsWith(".docx") || ruta.contains("..")) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "PLANTILLA_RUTA_INVALIDA", "La ruta de la plantilla no es válida.");
    }
    return ruta;
  }

  static ApiException datosIncompletos(List<String> faltantes, boolean analisisDisponible) {
    String mensaje =
        "No se puede generar el documento porque faltan "
            + (faltantes.size() == 1 ? "1 dato obligatorio." : faltantes.size() + " datos obligatorios.")
            + (analisisDisponible
                ? " Complétalos en el expediente o verifica que consten en el PDF analizado."
                : " El expediente no tiene un análisis IA completado; carga y analiza el PDF del juicio.");
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        PLANTILLA_DATOS_INCOMPLETOS,
        mensaje,
        List.of(),
        Map.of("variablesFaltantes", faltantes));
  }

  private static String nombrePdf(String plantilla) {
    String base = plantilla.replaceAll("(?i)\\.docx$", "");
    return base + ".pdf";
  }
}
