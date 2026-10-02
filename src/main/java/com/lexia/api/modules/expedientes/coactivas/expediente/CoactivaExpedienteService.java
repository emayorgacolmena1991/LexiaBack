package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage.StoredFile;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ArchivoItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CambioEtapaRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CreateExpedienteRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteDetalle;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.NotificacionItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.NotificacionRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ParticipanteItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ParticipanteRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.TimelineItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.UpdateExpedienteRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaExpedienteService {

  private static final Set<String> ROLES = Set.of("DEUDOR", "CODEUDOR", "GARANTE");
  private static final Set<String> TIPOS_PERSONA = Set.of("NATURAL", "JURIDICA");
  private static final Set<String> TIPOS_ID = Set.of("CEDULA", "RUC", "PASAPORTE");
  private static final Set<String> ACTOS = Set.of("RPV", "OPI", "PROVIDENCIA");
  private static final Set<String> MEDIOS = Set.of("BOLETA", "PERSONAL", "CORREO");
  private static final Set<String> TIPOS_ARCHIVO =
      Set.of(
          "EXPEDIENTE_ESCANEADO", "ACTA", "LIQUIDACION", "PROVIDENCIA", "OFICIO",
          "RESPUESTA_ENTIDAD", "ESCRITO", "EVIDENCIA", "OTRO");

  private final CoactivaExpedienteRepository expedientes;
  private final CoactivaParticipanteRepository participantes;
  private final CoactivaNotificacionRepository notificaciones;
  private final CoactivaEtapaHistorialRepository historial;
  private final CoactivaEventoRepository eventos;
  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final CoactivaDelegadoService delegados;
  private final CoactivaSemaforoService semaforo;
  private final CoactivaCaseSync caseSync;
  private final AuthorizationService authorization;

  public CoactivaExpedienteService(
      CoactivaExpedienteRepository expedientes,
      CoactivaParticipanteRepository participantes,
      CoactivaNotificacionRepository notificaciones,
      CoactivaEtapaHistorialRepository historial,
      CoactivaEventoRepository eventos,
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      CoactivaDelegadoService delegados,
      CoactivaSemaforoService semaforo,
      CoactivaCaseSync caseSync,
      AuthorizationService authorization) {
    this.expedientes = expedientes;
    this.participantes = participantes;
    this.notificaciones = notificaciones;
    this.historial = historial;
    this.eventos = eventos;
    this.archivos = archivos;
    this.storage = storage;
    this.delegados = delegados;
    this.semaforo = semaforo;
    this.caseSync = caseSync;
    this.authorization = authorization;
  }

  // ---------------------------------------------------------------------------
  // Expediente
  // ---------------------------------------------------------------------------

  @Transactional
  public ExpedienteDetalle crear(CreateExpedienteRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    String oficina = delegados.resolverCodigoOficina(tenantId, request.oficinaCodigo());
    CoactivaExpediente expediente =
        crearInterno(
            principal,
            new NuevoExpediente(
                request.nroJuicio(),
                request.nroOperacion(),
                oficina,
                request.delegadoId(),
                request.deudorNombre(),
                request.deudorIdentificacion(),
                request.etapaReportada(),
                request.fojas(),
                null,
                "MANUAL"));
    if (request.montoOriginal() != null) {
      expediente.setMontoOriginal(request.montoOriginal());
    }
    expediente.setObservaciones(CoactivaTexto.blankToNull(request.observaciones()));
    return detalle(tenantId, expediente);
  }

  /**
   * Alta del expediente + caso ECD + deudor. Usado por el alta manual y por la confirmación de
   * actas; el llamador valida permisos.
   */
  @Transactional
  public CoactivaExpediente crearInterno(AuthPrincipal principal, NuevoExpediente datos) {
    UUID tenantId = principal.tenantId();
    String juicio = CoactivaTexto.normalizarJuicio(datos.nroJuicio());
    if (juicio == null) {
      throw ApiException.badRequest("El número de juicio es obligatorio.");
    }
    if (expedientes.findByTenantIdAndNroJuicioAndDeletedAtIsNull(tenantId, juicio).isPresent()) {
      throw new ApiException(
          HttpStatus.CONFLICT, "COA_JUICIO_DUPLICADO", "Ya existe un expediente con el juicio " + juicio + ".");
    }
    String deudor = CoactivaTexto.sanitizarDeudor(datos.deudorNombre());
    String identificacion = CoactivaTexto.normalizarIdentificacion(datos.deudorIdentificacion());

    LegalCase legalCase =
        caseSync.crearCaso(tenantId, principal.userId(), principal.membershipId(), juicio, deudor, identificacion);
    CoactivaExpediente expediente =
        CoactivaExpediente.create(tenantId, legalCase.getId(), juicio, principal.userId());
    expediente.setDatosBase(
        CoactivaTexto.truncate(CoactivaTexto.blankToNull(datos.nroOperacion()), 40),
        CoactivaTexto.anioDeJuicio(juicio),
        datos.oficinaCodigo(),
        datos.fojas());
    String textoEtapa = CoactivaTexto.truncate(CoactivaTexto.blankToNull(datos.etapaReportada()), 160);
    expediente.setEtapaReportada(
        CoactivaEtapa.fromTextoLibre(textoEtapa).map(Enum::name).orElse(null), textoEtapa);
    expediente.setActaEntregaId(datos.actaId());

    UUID delegadoId = datos.delegadoId();
    if (delegadoId != null) {
      delegados.require(tenantId, delegadoId);
    } else {
      delegadoId =
          delegados
              .resolverUnico(tenantId, datos.oficinaCodigo(), LocalDate.now())
              .map(CoactivaDelegado::getId)
              .orElse(null);
    }
    expediente.setDelegadoId(delegadoId);
    expedientes.save(expediente);

    if (deudor != null) {
      CoactivaParticipante participante =
          CoactivaParticipante.create(
              tenantId, expediente.getId(), CoactivaParticipante.DEUDOR, 1, deudor, datos.fuente());
      if (identificacion != null) {
        participante.setIdentificacion(identificacion, tipoIdentificacionDe(identificacion));
      }
      participantes.save(participante);
    }
    registrarEvento(
        tenantId,
        expediente.getId(),
        "EXPEDIENTE_RECIBIDO",
        "Expediente recibido",
        datos.actaId() == null ? "Alta manual" : "Recibido por acta de entrega",
        principal.userId());
    recalcular(expediente);
    return expediente;
  }

  @Transactional(readOnly = true)
  public ExpedienteDetalle detalle(UUID id) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return detalle(tenantId, require(tenantId, id));
  }

  @Transactional
  public ExpedienteDetalle actualizar(UUID id, UpdateExpedienteRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, id);
    List<String> cambios = new ArrayList<>();

    if (request.nroOperacion() != null) {
      expediente.setNroOperacion(CoactivaTexto.blankToNull(request.nroOperacion()));
    }
    if (request.oficinaCodigo() != null) {
      expediente.setOficinaCodigo(delegados.resolverCodigoOficina(tenantId, request.oficinaCodigo()));
    }
    if (Boolean.TRUE.equals(request.quitarDelegado())) {
      expediente.setDelegadoId(null);
      cambios.add("Delegado removido");
    } else if (request.delegadoId() != null && !request.delegadoId().equals(expediente.getDelegadoId())) {
      CoactivaDelegado delegado = delegados.require(tenantId, request.delegadoId());
      expediente.setDelegadoId(delegado.getId());
      cambios.add("Delegado asignado: " + delegado.getNombre());
    }
    if (request.saeUserId() != null) {
      expediente.setSaeUserId(request.saeUserId());
    }
    if (request.asistenteUserId() != null) {
      expediente.setAsistenteUserId(request.asistenteUserId());
    }
    if (request.fojas() != null) {
      expediente.setFojas(request.fojas());
    }
    if (request.fechaCitacionOpi() != null) {
      expediente.setFechaCitacionOpi(request.fechaCitacionOpi());
    }
    if (request.montoOriginal() != null) {
      expediente.setMontoOriginal(request.montoOriginal());
    }
    if (request.convenioUsado() != null) {
      expediente.setConvenioUsado(request.convenioUsado());
    }
    if (request.suspendido() != null && request.suspendido() != expediente.isSuspendido()) {
      expediente.setSuspension(request.suspendido(), CoactivaTexto.blankToNull(request.suspensionMotivo()));
      cambios.add(request.suspendido() ? "Proceso suspendido" : "Suspensión levantada");
    }
    if (request.fechaUltimaActuacion() != null) {
      expediente.setFechaUltimaActuacion(request.fechaUltimaActuacion());
    }
    if (request.observaciones() != null) {
      expediente.setObservaciones(CoactivaTexto.blankToNull(request.observaciones()));
    }
    expediente.touch(principal.userId());
    for (String cambio : cambios) {
      registrarEvento(tenantId, id, "EXPEDIENTE_ACTUALIZADO", cambio, null, principal.userId());
    }
    recalcular(expediente);
    return detalle(tenantId, expediente);
  }

  @Transactional
  public ExpedienteDetalle cambiarEtapa(UUID id, CambioEtapaRequest request) {
    authorization.requirePermission(CoactivaPermisos.CONFIRMAR_DIAGNOSTICO);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, id);
    CoactivaEtapa nueva =
        CoactivaEtapa.parse(request.etapa())
            .orElseThrow(() -> ApiException.badRequest("Etapa procesal desconocida: " + request.etapa()));
    CoactivaEtapa actual = CoactivaEtapa.parse(expediente.getEtapaVerificada()).orElse(null);
    boolean forzar = Boolean.TRUE.equals(request.forzar());

    if (actual != null && actual != nueva && !forzar
        && !caseSync.transicionPermitida(tenantId, expediente.getCaseId(), actual, nueva)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "COA_TRANSICION_INVALIDA",
          "No se permite pasar de «" + actual.label() + "» a «" + nueva.label() + "». Usa «forzar» con motivo.");
    }
    if (forzar && CoactivaTexto.blankToNull(request.motivo()) == null) {
      throw ApiException.badRequest("Indica el motivo para forzar el cambio de etapa.");
    }

    expediente.confirmarEtapa(nueva.name(), principal.userId());
    expediente.setEstadoOperativo(
        nueva == CoactivaEtapa.ARCHIVADO ? CoactivaExpediente.ARCHIVADO : CoactivaExpediente.VERIFICADO);
    expediente.touch(principal.userId());
    historial.save(
        CoactivaEtapaHistorial.create(
            tenantId,
            id,
            actual == null ? null : actual.name(),
            nueva.name(),
            CoactivaTexto.truncate(CoactivaTexto.blankToNull(request.motivo()), 600),
            principal.userId()));
    caseSync.sincronizarEtapa(tenantId, expediente.getCaseId(), nueva);
    recalcular(expediente);
    return detalle(tenantId, expediente);
  }

  @Transactional(readOnly = true)
  public List<TimelineItem> timeline(UUID id) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    require(tenantId, id);
    List<TimelineItem> items = new ArrayList<>();
    for (CoactivaEtapaHistorial h : historial.findByTenantIdAndExpedienteIdOrderByCreatedAtAsc(tenantId, id)) {
      String desde = h.getEtapaAnterior() == null ? "sin etapa" : etapaLabel(h.getEtapaAnterior());
      items.add(
          new TimelineItem(
              "ETAPA",
              "Etapa: " + etapaLabel(h.getEtapaNueva()),
              "Desde " + desde + (h.getMotivo() == null ? "" : ". " + h.getMotivo()),
              h.getCreatedAt(),
              h.getUsuarioId()));
    }
    for (CoactivaEvento e : eventos.findByTenantIdAndExpedienteIdOrderByCreatedAtAsc(tenantId, id)) {
      items.add(new TimelineItem(e.getTipo(), e.getTitulo(), e.getDetalle(), e.getCreatedAt(), e.getUsuarioId()));
    }
    for (CoactivaArchivo a :
        archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, id)) {
      items.add(
          new TimelineItem(
              "ARCHIVO",
              "Archivo cargado: " + a.getNombreOriginal(),
              a.getTipo(),
              a.getCreatedAt(),
              a.getSubidoPor()));
    }
    items.sort(Comparator.comparing(TimelineItem::fecha).reversed());
    return items;
  }

  // ---------------------------------------------------------------------------
  // Participantes
  // ---------------------------------------------------------------------------

  @Transactional
  public ParticipanteItem crearParticipante(UUID expedienteId, ParticipanteRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    String rol = validarEnum(request.rol(), ROLES, "rol");
    List<CoactivaParticipante> actuales =
        participantes.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(tenantId, expedienteId);
    int orden = request.orden() != null ? request.orden() : actuales.size() + 1;
    CoactivaParticipante participante =
        CoactivaParticipante.create(
            tenantId, expedienteId, rol, orden, CoactivaTexto.nombrePropio(request.nombreCompleto()), "MANUAL");
    aplicarParticipante(participante, request, rol, orden);
    participantes.save(participante);
    registrarEvento(
        tenantId, expedienteId, "PARTICIPANTE", "Participante agregado: " + participante.getNombreCompleto(),
        rol, principal.userId());
    expediente.touch(principal.userId());
    recalcular(expediente);
    return toItem(participante, Set.of());
  }

  @Transactional
  public ParticipanteItem actualizarParticipante(UUID expedienteId, UUID participanteId, ParticipanteRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    CoactivaParticipante participante = requireParticipante(tenantId, expedienteId, participanteId);
    String rol = validarEnum(request.rol(), ROLES, "rol");
    int orden = request.orden() != null ? request.orden() : participante.getOrden();
    aplicarParticipante(participante, request, rol, orden);
    expediente.touch(principal.userId());
    recalcular(expediente);
    return toItem(participante, Set.of());
  }

  @Transactional
  public void eliminarParticipante(UUID expedienteId, UUID participanteId) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    CoactivaParticipante participante = requireParticipante(tenantId, expedienteId, participanteId);
    participante.softDelete();
    registrarEvento(
        tenantId, expedienteId, "PARTICIPANTE", "Participante eliminado: " + participante.getNombreCompleto(),
        null, principal.userId());
    expediente.touch(principal.userId());
    recalcular(expediente);
  }

  // ---------------------------------------------------------------------------
  // Notificaciones
  // ---------------------------------------------------------------------------

  @Transactional
  public NotificacionItem crearNotificacion(UUID expedienteId, NotificacionRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    CoactivaParticipante participante =
        request.participanteId() == null
            ? null
            : requireParticipante(tenantId, expedienteId, request.participanteId());
    if (request.archivoId() != null) {
      requireArchivo(tenantId, request.archivoId());
    }
    CoactivaNotificacion notificacion =
        notificaciones.save(
            CoactivaNotificacion.create(
                tenantId,
                expedienteId,
                participante == null ? null : participante.getId(),
                validarEnum(request.acto(), ACTOS, "acto"),
                validarEnum(request.medio(), MEDIOS, "medio"),
                request.numeroBoleta(),
                request.fecha(),
                request.archivoId(),
                request.paginaDesde(),
                request.paginaHasta(),
                request.valida() == null || request.valida(),
                "MANUAL",
                CoactivaTexto.blankToNull(request.observacion()),
                principal.userId()));
    if ("OPI".equals(notificacion.getActo()) && request.fecha() != null
        && (expediente.getFechaCitacionOpi() == null || request.fecha().isAfter(expediente.getFechaCitacionOpi()))) {
      expediente.setFechaCitacionOpi(request.fecha());
    }
    registrarEvento(
        tenantId,
        expedienteId,
        "NOTIFICACION",
        "Notificación " + notificacion.getActo() + " (" + notificacion.getMedio().toLowerCase(Locale.ROOT) + ")",
        participante == null ? null : participante.getNombreCompleto(),
        principal.userId());
    expediente.touch(principal.userId());
    recalcular(expediente);
    return toItem(notificacion, participante == null ? null : participante.getNombreCompleto());
  }

  @Transactional
  public void eliminarNotificacion(UUID expedienteId, UUID notificacionId) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    CoactivaNotificacion notificacion =
        notificaciones
            .findByIdAndTenantIdAndDeletedAtIsNull(notificacionId, tenantId)
            .filter(n -> n.getExpedienteId().equals(expedienteId))
            .orElseThrow(() -> ApiException.notFound("Notificación no encontrada."));
    notificacion.softDelete();
    expediente.touch(principal.userId());
    recalcular(expediente);
  }

  // ---------------------------------------------------------------------------
  // Archivos
  // ---------------------------------------------------------------------------

  @Transactional
  public ArchivoItem subirArchivo(UUID expedienteId, MultipartFile file, String tipo) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaExpediente expediente = require(tenantId, expedienteId);
    String tipoArchivo =
        tipo == null || tipo.isBlank() ? CoactivaArchivo.EXPEDIENTE_ESCANEADO : validarEnum(tipo, TIPOS_ARCHIVO, "tipo");
    CoactivaArchivo archivo = guardarArchivo(principal, file, tipoArchivo);
    vincular(principal, archivo, expediente);
    return toItem(archivo);
  }

  /** Guarda el archivo en disco y lo registra SIN_ASIGNAR. */
  @Transactional
  public CoactivaArchivo guardarArchivo(AuthPrincipal principal, MultipartFile file, String tipo) {
    UUID id = UUID.randomUUID();
    StoredFile stored = storage.store(principal.tenantId(), id, file);
    CoactivaArchivo archivo =
        CoactivaArchivo.create(
            id,
            principal.tenantId(),
            tipo,
            CoactivaTexto.truncate(file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename(), 400),
            file.getContentType(),
            stored.size(),
            stored.sha256(),
            stored.path(),
            principal.userId());
    archivo.setNroJuicioDetectado(CoactivaTexto.detectarJuicio(file.getOriginalFilename()).orElse(null));
    return archivos.save(archivo);
  }

  /** Vincula un archivo al expediente; un expediente RECIBIDO con escaneo pasa a DIGITALIZADO. */
  @Transactional
  public void vincular(AuthPrincipal principal, CoactivaArchivo archivo, CoactivaExpediente expediente) {
    archivo.vincularExpediente(expediente.getId());
    if (CoactivaArchivo.EXPEDIENTE_ESCANEADO.equals(archivo.getTipo())
        && CoactivaExpediente.RECIBIDO.equals(expediente.getEstadoOperativo())) {
      expediente.setEstadoOperativo(CoactivaExpediente.DIGITALIZADO);
      registrarEvento(
          principal.tenantId(),
          expediente.getId(),
          "EXPEDIENTE_DIGITALIZADO",
          "Expediente digitalizado",
          archivo.getNombreOriginal(),
          principal.userId());
    }
    expediente.touch(principal.userId());
    recalcular(expediente);
  }

  @Transactional(readOnly = true)
  public List<ArchivoItem> listarArchivos(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    require(tenantId, expedienteId);
    return archivos
        .findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, expedienteId)
        .stream()
        .map(CoactivaExpedienteService::toItem)
        .toList();
  }

  @Transactional(readOnly = true)
  public ArchivoContenido contenido(UUID archivoId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    CoactivaArchivo archivo = requireArchivo(tenantId, archivoId);
    return new ArchivoContenido(archivo.getNombreOriginal(), archivo.getMimeType(), storage.read(archivo));
  }

  // ---------------------------------------------------------------------------
  // Soporte
  // ---------------------------------------------------------------------------

  public CoactivaExpediente require(UUID tenantId, UUID id) {
    return expedientes
        .findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente coactivo no encontrado."));
  }

  public CoactivaArchivo requireArchivo(UUID tenantId, UUID id) {
    return archivos
        .findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
        .orElseThrow(() -> ApiException.notFound("Archivo no encontrado."));
  }

  public void recalcular(CoactivaExpediente expediente) {
    UUID tenantId = expediente.getTenantId();
    semaforo.aplicar(
        expediente,
        participantes.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(tenantId, expediente.getId()),
        notificaciones.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByFechaAscCreatedAtAsc(
            tenantId, expediente.getId()),
        delegados.buscar(tenantId, expediente.getDelegadoId()).orElse(null));
  }

  public void registrarEvento(
      UUID tenantId, UUID expedienteId, String tipo, String titulo, String detalle, UUID userId) {
    eventos.save(CoactivaEvento.create(tenantId, expedienteId, tipo, titulo, detalle, userId));
  }

  public static String etapaLabel(String etapa) {
    return CoactivaEtapa.parse(etapa).map(CoactivaEtapa::label).orElse(etapa);
  }

  static String tipoIdentificacionDe(String identificacion) {
    String digits = CoactivaTexto.soloDigitos(identificacion);
    if (digits.length() == 13 && digits.equals(identificacion)) {
      return "RUC";
    }
    if (digits.length() == 10 && digits.equals(identificacion)) {
      return "CEDULA";
    }
    return "PASAPORTE";
  }

  private ExpedienteDetalle detalle(UUID tenantId, CoactivaExpediente expediente) {
    List<CoactivaParticipante> lista =
        participantes.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(tenantId, expediente.getId());
    List<CoactivaNotificacion> notifs =
        notificaciones.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByFechaAscCreatedAtAsc(
            tenantId, expediente.getId());
    CoactivaDelegado delegado = delegados.buscar(tenantId, expediente.getDelegadoId()).orElse(null);
    CoactivaSemaforoService.Evaluacion evaluacion = semaforo.evaluar(expediente, lista, notifs, delegado);
    Set<UUID> sinNotificar = evaluacion.sinNotificarOpi();
    Map<UUID, String> nombres =
        lista.stream().collect(Collectors.toMap(CoactivaParticipante::getId, CoactivaParticipante::getNombreCompleto));
    CoactivaEtapa actual = CoactivaEtapa.parse(expediente.getEtapaVerificada()).orElse(null);
    List<String> permitidas =
        actual == null
            ? Arrays.stream(CoactivaEtapa.values()).map(Enum::name).toList()
            : caseSync.etapasSiguientes(tenantId, expediente.getCaseId(), actual);
    String caseCode = caseSync.caso(tenantId, expediente.getCaseId()).map(LegalCase::getCode).orElse(null);

    return new ExpedienteDetalle(
        expediente.getId(),
        expediente.getCaseId(),
        caseCode,
        expediente.getNroJuicio(),
        expediente.getNroOperacion(),
        expediente.getAnio(),
        expediente.getOficinaCodigo(),
        delegado == null ? null : toDelegadoItem(delegado),
        expediente.getSaeUserId(),
        expediente.getAsistenteUserId(),
        expediente.getFojas(),
        expediente.getEstadoOperativo(),
        expediente.getEtapaReportada(),
        expediente.getEtapaReportadaTexto(),
        expediente.getEtapaVerificada(),
        expediente.getEtapaVerificada() == null ? null : etapaLabel(expediente.getEtapaVerificada()),
        expediente.getEtapaSugeridaIa(),
        expediente.getEtapaConfirmadaAt(),
        expediente.getSemaforo(),
        expediente.getSemaforoMotivo(),
        evaluacion.siguienteAccion(),
        expediente.getFechaCitacionOpi(),
        expediente.getMontoOriginal(),
        expediente.isConvenioUsado(),
        expediente.isSuspendido(),
        expediente.getSuspensionMotivo(),
        expediente.getActaEntregaId(),
        expediente.getFechaUltimaActuacion(),
        expediente.getObservaciones(),
        lista.stream().map(p -> toItem(p, sinNotificar)).toList(),
        notifs.stream().map(n -> toItem(n, nombres.get(n.getParticipanteId()))).toList(),
        permitidas,
        expediente.getCreatedAt(),
        expediente.getUpdatedAt(),
        expediente.getRowVersion());
  }

  private void aplicarParticipante(
      CoactivaParticipante participante, ParticipanteRequest request, String rol, int orden) {
    String identificacion = CoactivaTexto.normalizarIdentificacion(request.identificacion());
    String tipoId =
        request.tipoIdentificacion() == null
            ? (identificacion == null ? "CEDULA" : tipoIdentificacionDe(identificacion))
            : validarEnum(request.tipoIdentificacion(), TIPOS_ID, "tipoIdentificacion");
    String tipoPersona =
        request.tipoPersona() == null
            ? ("RUC".equals(tipoId) && identificacion != null && identificacion.charAt(2) >= '6' ? "JURIDICA" : "NATURAL")
            : validarEnum(request.tipoPersona(), TIPOS_PERSONA, "tipoPersona");
    String emails =
        request.emails() == null
            ? null
            : request.emails().stream()
                .map(CoactivaTexto::blankToNull)
                .filter(e -> e != null)
                .map(e -> e.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.joining(","));
    participante.update(
        rol,
        orden,
        tipoPersona,
        tipoId,
        identificacion,
        CoactivaTexto.nombrePropio(request.nombreCompleto()),
        emails == null || emails.isEmpty() ? null : emails,
        CoactivaTexto.blankToNull(request.direccion()),
        CoactivaTexto.blankToNull(request.telefono()),
        Boolean.TRUE.equals(request.verificado()));
  }

  private CoactivaParticipante requireParticipante(UUID tenantId, UUID expedienteId, UUID participanteId) {
    return participantes
        .findByIdAndTenantIdAndDeletedAtIsNull(participanteId, tenantId)
        .filter(p -> p.getExpedienteId().equals(expedienteId))
        .orElseThrow(() -> ApiException.notFound("Participante no encontrado."));
  }

  private static String validarEnum(String value, Set<String> allowed, String campo) {
    String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    if (normalized == null || !allowed.contains(normalized)) {
      throw ApiException.badRequest("Valor inválido para " + campo + ": " + value);
    }
    return normalized;
  }

  static ParticipanteItem toItem(CoactivaParticipante p, Set<UUID> sinNotificarOpi) {
    List<String> emails =
        p.getEmails() == null ? List.of() : Arrays.stream(p.getEmails().split(",")).map(String::trim).toList();
    boolean valida =
        p.getIdentificacion() != null
            && ("PASAPORTE".equals(p.getTipoIdentificacion()) || CoactivaTexto.cedulaValida(p.getIdentificacion())
                || ("RUC".equals(p.getTipoIdentificacion()) && p.getIdentificacion().length() == 13));
    return new ParticipanteItem(
        p.getId(),
        p.getRol(),
        p.getOrden(),
        p.getTipoPersona(),
        p.getTipoIdentificacion(),
        p.getIdentificacion(),
        valida,
        p.getNombreCompleto(),
        emails,
        p.getDireccion(),
        p.getTelefono(),
        p.getFuente(),
        p.getConfianzaIa(),
        p.isVerificado(),
        !sinNotificarOpi.contains(p.getId()));
  }

  static NotificacionItem toItem(CoactivaNotificacion n, String participanteNombre) {
    return new NotificacionItem(
        n.getId(),
        n.getParticipanteId(),
        participanteNombre,
        n.getActo(),
        n.getMedio(),
        n.getNumeroBoleta(),
        n.getFecha(),
        n.getArchivoId(),
        n.getPaginaDesde(),
        n.getPaginaHasta(),
        n.isValida(),
        n.getFuente(),
        n.getObservacion());
  }

  public static ArchivoItem toItem(CoactivaArchivo a) {
    return new ArchivoItem(
        a.getId(),
        a.getExpedienteId(),
        a.getTipo(),
        a.getNombreOriginal(),
        a.getMimeType(),
        a.getTamanoBytes(),
        a.getNroJuicioDetectado(),
        a.getEstadoVinculo(),
        a.getCreatedAt());
  }

  private static DelegadoItem toDelegadoItem(CoactivaDelegado d) {
    return new DelegadoItem(
        d.getId(),
        d.getNombre(),
        d.getIdentificacion(),
        d.getCargo(),
        d.getResolucionNumero(),
        d.getResolucionFecha(),
        d.getVigenteDesde(),
        d.getVigenteHasta(),
        d.getEmail(),
        d.isActivo(),
        d.vigenteEn(LocalDate.now()),
        List.of());
  }

  public record NuevoExpediente(
      String nroJuicio,
      String nroOperacion,
      String oficinaCodigo,
      UUID delegadoId,
      String deudorNombre,
      String deudorIdentificacion,
      String etapaReportada,
      Integer fojas,
      UUID actaId,
      String fuente) {}

  public record ArchivoContenido(String nombre, String mimeType, byte[] bytes) {}
}
