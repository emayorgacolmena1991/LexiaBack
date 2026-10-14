package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoRequest;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.ResolucionDelegadoRequest;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaBandejaService.Filtro;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.AnalisisResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.BatchConfirmarRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ConfirmarEtapaRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.TareaSugeridaItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ArchivoItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.BandejaResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CambioEtapaRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CatalogosResponse;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.CreateExpedienteRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteDetalle;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteSelectorItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.NotificacionItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.NotificacionRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.OverrideIaRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ParticipanteItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ParticipanteRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.TimelineItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.UpdateExpedienteRequest;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService.ArchivoContenido;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/coactivas")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaExpedienteController {

  private final CoactivaExpedienteService service;
  private final CoactivaBandejaService bandeja;
  private final CoactivaDelegadoService delegados;
  private final BatchEventPublisherService sse;

  public CoactivaExpedienteController(
      CoactivaExpedienteService service,
      CoactivaBandejaService bandeja,
      CoactivaDelegadoService delegados,
      BatchEventPublisherService sse) {
    this.service = service;
    this.bandeja = bandeja;
    this.delegados = delegados;
    this.sse = sse;
  }

  @GetMapping("/catalogos")
  public CatalogosResponse catalogos() {
    return bandeja.catalogos();
  }

  @GetMapping("/delegados")
  public List<DelegadoItem> delegados() {
    return delegados.delegadosActivos();
  }

  @PutMapping("/delegados/{id}")
  public DelegadoItem actualizarDelegado(@PathVariable UUID id, @Valid @RequestBody DelegadoRequest request) {
    return delegados.actualizarDelegado(id, request);
  }

  @PutMapping("/delegados/{id}/resolucion")
  public DelegadoItem actualizarResolucion(
      @PathVariable UUID id, @Valid @RequestBody ResolucionDelegadoRequest request) {
    return delegados.actualizarResolucion(id, request.resolucionNumero(), request.resolucionFecha());
  }

  @GetMapping("/oficinas")
  public List<OficinaItem> oficinas() {
    return delegados.oficinasActivas();
  }

  @GetMapping("/expedientes")
  public BandejaResponse listar(
      @RequestParam(required = false) String oficina,
      @RequestParam(required = false) UUID delegadoId,
      @RequestParam(required = false) String etapa,
      @RequestParam(required = false) String semaforo,
      @RequestParam(required = false) String estadoOperativo,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean sugerenciaIa,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return bandeja.listar(
        new Filtro(oficina, delegadoId, etapa, semaforo, estadoOperativo, q, sugerenciaIa), page, size);
  }

  @GetMapping("/expedientes/selector")
  public List<ExpedienteSelectorItem> selector(
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "true") boolean soloSinDocumento) {
    return bandeja.selector(query, limit, soloSinDocumento);
  }

  @PostMapping("/expedientes")
  @ResponseStatus(HttpStatus.CREATED)
  public ExpedienteDetalle crear(@Valid @RequestBody CreateExpedienteRequest request) {
    return service.crear(request);
  }

  @GetMapping("/expedientes/{id}")
  public ExpedienteDetalle detalle(@PathVariable UUID id) {
    return service.detalle(id);
  }

  @GetMapping(value = "/expedientes/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID id) {
    service.detalle(id);
    return sse.abrir(id);
  }

  @PatchMapping("/expedientes/{id}")
  public ExpedienteDetalle actualizar(@PathVariable UUID id, @Valid @RequestBody UpdateExpedienteRequest request) {
    return service.actualizar(id, request);
  }

  @PostMapping("/expedientes/{id}/etapa")
  public ExpedienteDetalle cambiarEtapa(@PathVariable UUID id, @Valid @RequestBody CambioEtapaRequest request) {
    return service.cambiarEtapa(id, request);
  }

  @GetMapping("/expedientes/{id}/timeline")
  public List<TimelineItem> timeline(@PathVariable UUID id) {
    return service.timeline(id);
  }

  @PostMapping("/expedientes/{id}/participantes")
  @ResponseStatus(HttpStatus.CREATED)
  public ParticipanteItem crearParticipante(
      @PathVariable UUID id, @Valid @RequestBody ParticipanteRequest request) {
    return service.crearParticipante(id, request);
  }

  @PutMapping("/expedientes/{id}/participantes/{participanteId}")
  public ParticipanteItem actualizarParticipante(
      @PathVariable UUID id,
      @PathVariable UUID participanteId,
      @Valid @RequestBody ParticipanteRequest request) {
    return service.actualizarParticipante(id, participanteId, request);
  }

  @DeleteMapping("/expedientes/{id}/participantes/{participanteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminarParticipante(@PathVariable UUID id, @PathVariable UUID participanteId) {
    service.eliminarParticipante(id, participanteId);
  }

  @PostMapping("/expedientes/{id}/notificaciones")
  @ResponseStatus(HttpStatus.CREATED)
  public NotificacionItem crearNotificacion(
      @PathVariable UUID id, @Valid @RequestBody NotificacionRequest request) {
    return service.crearNotificacion(id, request);
  }

  @DeleteMapping("/expedientes/{id}/notificaciones/{notificacionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminarNotificacion(@PathVariable UUID id, @PathVariable UUID notificacionId) {
    service.eliminarNotificacion(id, notificacionId);
  }

  @GetMapping("/expedientes/{id}/archivos")
  public List<ArchivoItem> archivos(@PathVariable UUID id) {
    return service.listarArchivos(id);
  }

  @PostMapping(value = "/expedientes/{id}/archivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ArchivoItem subirArchivo(
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String tipo) {
    return service.subirArchivo(id, file, tipo);
  }

  @PostMapping(value = "/expedientes/{id}/documento-unificado", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ArchivoItem documentoUnificado(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
    return service.subirDocumentoUnificado(id, file);
  }

  @GetMapping("/expedientes/{id}/tareas-sugeridas")
  public List<TareaSugeridaItem> tareasSugeridas(@PathVariable UUID id) {
    return service.tareasSugeridas(id);
  }

  @PostMapping("/expedientes/{id}/tareas-sugeridas/{tareaId}/incorporar")
  public TareaSugeridaItem incorporarTarea(@PathVariable UUID id, @PathVariable UUID tareaId) {
    return service.incorporarTarea(id, tareaId);
  }

  @PostMapping("/expedientes/{id}/tareas-sugeridas/{tareaId}/descartar")
  public TareaSugeridaItem descartarTarea(@PathVariable UUID id, @PathVariable UUID tareaId) {
    return service.descartarTarea(id, tareaId);
  }

  @PostMapping("/expedientes/{id}/confirmar-etapa")
  public ExpedienteDetalle confirmarEtapa(
      @PathVariable UUID id, @Valid @RequestBody ConfirmarEtapaRequest request) {
    return service.confirmarEtapa(id, request);
  }

  @PostMapping("/expedientes/batch-confirmar")
  public List<ExpedienteDetalle> batchConfirmar(@Valid @RequestBody BatchConfirmarRequest request) {
    return service.batchConfirmar(request);
  }

  @GetMapping("/expedientes/{id}/analisis")
  public AnalisisResponse analisis(@PathVariable UUID id) {
    return service.obtenerAnalisis(id);
  }

  @PostMapping("/expedientes/{id}/analisis")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AnalisisResponse reanalizar(@PathVariable UUID id) {
    return service.reanalizar(id);
  }

  @GetMapping("/archivos/{archivoId}")
  public ArchivoItem archivo(@PathVariable UUID archivoId) {
    return service.obtenerArchivo(archivoId);
  }

  @PatchMapping("/archivos/{archivoId}/override-ia")
  public ArchivoItem overrideIa(
      @PathVariable UUID archivoId, @RequestBody(required = false) @Valid OverrideIaRequest request) {
    return service.overrideIa(archivoId, request);
  }

  @GetMapping("/archivos/{archivoId}/contenido")
  public ResponseEntity<byte[]> contenido(@PathVariable UUID archivoId) {
    ArchivoContenido contenido = service.contenido(archivoId);
    MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
    try {
      if (contenido.mimeType() != null) {
        mediaType = MediaType.parseMediaType(contenido.mimeType());
      }
    } catch (org.springframework.http.InvalidMediaTypeException ignored) {
      // MIME declarado por el cliente al subir: si es ilegible se descarga como binario.
    }
    // El MIME lo declara quien sube: HTML o SVG inline ejecutarían script en el origen de Lexia.
    boolean inline =
        MediaType.APPLICATION_PDF.equalsTypeAndSubtype(mediaType)
            || ("image".equals(mediaType.getType())
                && !mediaType.isWildcardSubtype()
                && !mediaType.getSubtype().contains("svg"));
    if (!inline) {
      mediaType = MediaType.APPLICATION_OCTET_STREAM;
    }
    ContentDisposition disposition =
        (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(contenido.nombre(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(contenido.bytes());
  }
}
