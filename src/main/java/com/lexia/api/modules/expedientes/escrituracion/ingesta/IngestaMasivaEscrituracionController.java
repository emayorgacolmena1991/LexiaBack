package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.AsignacionesPayload;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.CrearLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.LoteStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/v1/escrituracion/ingesta-masiva")
public class IngestaMasivaEscrituracionController {

  private final IngestaMasivaEscrituracionService ingesta;

  public IngestaMasivaEscrituracionController(IngestaMasivaEscrituracionService ingesta) {
    this.ingesta = ingesta;
  }

  @PostMapping("/batch")
  public LoteStatus crear(@Valid @RequestBody CrearLoteRequest request) {
    return ingesta.crear(request);
  }

  @PostMapping(value = "/{batchId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LoteStatus subir(
      @PathVariable UUID batchId,
      @Valid @RequestPart("asignaciones") AsignacionesPayload asignaciones,
      MultipartHttpServletRequest request) {
    return ingesta.subir(batchId, asignaciones, request.getFiles("files"));
  }

  @PostMapping(value = "/{batchId}/filas/{clientRowId}/reemplazar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LoteStatus reemplazar(
      @PathVariable UUID batchId,
      @PathVariable String clientRowId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "tipoDocumento", required = false) String tipoDocumento,
      @RequestParam(value = "fileId", required = false) String fileId) {
    return ingesta.reemplazar(batchId, clientRowId, file, tipoDocumento, fileId);
  }

  @PostMapping("/{batchId}/ejecutar-ocr")
  public LoteStatus ejecutarOcr(@PathVariable UUID batchId) {
    return ingesta.ejecutarOcr(batchId);
  }

  @PostMapping("/{batchId}/filas/{clientRowId}/reintentar")
  public LoteStatus reintentar(@PathVariable UUID batchId, @PathVariable String clientRowId) {
    return ingesta.reintentar(batchId, clientRowId);
  }

  @GetMapping("/{batchId}/status")
  public LoteStatus status(@PathVariable UUID batchId) {
    return ingesta.status(batchId);
  }

  @PostMapping("/{batchId}/process-ia")
  public LoteStatus procesarIa(@PathVariable UUID batchId) {
    return ingesta.procesarIa(batchId);
  }
}
