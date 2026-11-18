package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.AsignacionesPayload;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ConfirmarLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.CrearLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ExpedienteEnLoteDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LoteListItemDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LoteStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.OverrideRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LotesPageDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ResumenLote;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Lecturas piden expedientes:caso:leer; cualquier cambio de lote, expedientes:caso:escribir. */
@RestController
@RequestMapping("/api/v1/escrituracion/ingesta-masiva")
public class IngestaMasivaEscrituracionController {

  private final IngestaMasivaEscrituracionService ingesta;
  private final ObjectProvider<AuthorizationService> authorization;

  public IngestaMasivaEscrituracionController(
      IngestaMasivaEscrituracionService ingesta,
      ObjectProvider<AuthorizationService> authorization) {
    this.ingesta = ingesta;
    this.authorization = authorization;
  }

  @GetMapping("/lotes")
  public LotesPageDto listarLotes(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String producto,
      @RequestParam(required = false) String canton,
      @RequestParam(required = false) String q) {
    PermisoExpediente.leer(authorization);
    return ingesta.listarLotes(page, size, status, producto, canton, q);
  }

  @GetMapping("/lotes/{batchId}")
  public LoteListItemDto detalleLote(@PathVariable UUID batchId) {
    PermisoExpediente.leer(authorization);
    return ingesta.detalleLote(batchId);
  }

  @PostMapping("/batch")
  public LoteStatus crear(@Valid @RequestBody CrearLoteRequest request) {
    PermisoExpediente.escribir(authorization);
    return ingesta.crear(request);
  }

  @PostMapping(value = "/{batchId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LoteStatus subir(
      @PathVariable UUID batchId,
      @Valid @RequestPart("asignaciones") AsignacionesPayload asignaciones,
      MultipartHttpServletRequest request) {
    PermisoExpediente.escribir(authorization);
    return ingesta.subir(batchId, asignaciones, request.getFiles("files"));
  }

  @PostMapping(
      value = "/{batchId}/items/{itemId}/reemplazar",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LoteStatus reemplazar(
      @PathVariable UUID batchId,
      @PathVariable("itemId") String clientRowId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "tipoDocumento", required = false) String tipoDocumento,
      @RequestParam(value = "fileId", required = false) String fileId) {
    PermisoExpediente.escribir(authorization);
    return ingesta.reemplazar(batchId, clientRowId, file, tipoDocumento, fileId);
  }

  @PostMapping("/{batchId}/ejecutar-ocr")
  public LoteStatus ejecutarOcr(@PathVariable UUID batchId) {
    PermisoExpediente.escribir(authorization);
    return ingesta.ejecutarOcr(batchId);
  }

  @PostMapping("/{batchId}/items/{itemId}/reintentar")
  public LoteStatus reintentar(@PathVariable UUID batchId, @PathVariable String itemId) {
    PermisoExpediente.escribir(authorization);
    return ingesta.reintentarItem(batchId, itemId);
  }

  @GetMapping("/{batchId}/resumen")
  public ResumenLote resumen(@PathVariable UUID batchId) {
    PermisoExpediente.leer(authorization);
    return ingesta.resumen(batchId);
  }

  @GetMapping(value = "/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID batchId) {
    if (AuthContext.get() == null) {
      throw AuthException.unauthorized("Sesión no válida o vencida.");
    }
    PermisoExpediente.leer(authorization);
    return ingesta.stream(batchId);
  }

  @GetMapping("/{batchId}/expedientes")
  public List<ExpedienteEnLoteDto> listarExpedientesLote(@PathVariable UUID batchId) {
    PermisoExpediente.leer(authorization);
    return ingesta.listarExpedientesLote(batchId);
  }

  @PostMapping("/{batchId}/process-ia")
  public LoteStatus procesarIa(@PathVariable UUID batchId) {
    PermisoExpediente.escribir(authorization);
    return ingesta.procesarIa(batchId);
  }

  @PostMapping("/{batchId}/expedientes/{expedienteId}/override")
  public ResponseEntity<LoteStatus> overrideExpediente(
      @PathVariable UUID batchId,
      @PathVariable UUID expedienteId,
      @RequestBody OverrideRequest request) {
    PermisoExpediente.escribir(authorization);
    return ResponseEntity.ok(ingesta.overrideExpediente(batchId, expedienteId, request.motivo()));
  }

  @PatchMapping("/{batchId}/confirmar")
  public ResponseEntity<LoteStatus> confirmarLote(
      @PathVariable UUID batchId,
      @RequestBody ConfirmarLoteRequest request) {
    PermisoExpediente.escribir(authorization);
    return ResponseEntity.ok(ingesta.confirmarLote(batchId, request.confirmadoPor()));
  }
}
