package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.AsignacionesPayload;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.CrearLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.LoteStatus;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

/** Todo responde 202: OCR e IA corren en {@code app.jobs}; el progreso llega por {@code /stream}. */
@RestController
@RequestMapping("/api/v1/escrituracion/ingesta-masiva")
public class IngestaMasivaEscrituracionController {

  private final IngestaMasivaEscrituracionService ingesta;

  public IngestaMasivaEscrituracionController(IngestaMasivaEscrituracionService ingesta) {
    this.ingesta = ingesta;
  }

  @PostMapping("/batch")
  public ResponseEntity<LoteStatus> crear(@Valid @RequestBody CrearLoteRequest request) {
    return ResponseEntity.accepted().body(ingesta.crear(request));
  }

  @PostMapping(value = "/{batchId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<LoteStatus> subir(
      @PathVariable UUID batchId,
      @Valid @RequestPart("asignaciones") AsignacionesPayload asignaciones,
      MultipartHttpServletRequest request) {
    return ResponseEntity.accepted().body(ingesta.subir(batchId, asignaciones, request.getFiles("files")));
  }

  @PostMapping(
      value = {"/{batchId}/filas/{itemId}/reemplazar", "/{batchId}/reemplazar/{itemId}"},
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<LoteStatus> reemplazar(
      @PathVariable UUID batchId,
      @PathVariable String itemId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "tipoDocumento", required = false) String tipoDocumento,
      @RequestParam(value = "fileId", required = false) String fileId) {
    return ResponseEntity.accepted().body(ingesta.reemplazar(batchId, itemId, file, tipoDocumento, fileId));
  }

  @PostMapping("/{batchId}/ejecutar-ocr")
  public ResponseEntity<LoteStatus> ejecutarOcr(@PathVariable UUID batchId) {
    return ResponseEntity.accepted().body(ingesta.ejecutarOcr(batchId));
  }

  @PostMapping({"/{batchId}/filas/{itemId}/reintentar", "/{batchId}/reintentar/{itemId}"})
  public ResponseEntity<LoteStatus> reintentar(@PathVariable UUID batchId, @PathVariable String itemId) {
    return ResponseEntity.accepted().body(ingesta.reintentar(batchId, itemId));
  }

  @GetMapping("/{batchId}/status")
  public ResponseEntity<Map<String, String>> status(@PathVariable UUID batchId) {
    return ResponseEntity.status(HttpStatus.GONE)
        .body(Map.of("message", "GET /status fue retirado. Usa GET /" + batchId + "/stream (SSE)."));
  }

  @GetMapping(value = "/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID batchId) {
    return ingesta.stream(batchId);
  }

  @PostMapping("/{batchId}/process-ia")
  public ResponseEntity<LoteStatus> procesarIa(@PathVariable UUID batchId) {
    return ResponseEntity.accepted().body(ingesta.procesarIa(batchId));
  }
}
