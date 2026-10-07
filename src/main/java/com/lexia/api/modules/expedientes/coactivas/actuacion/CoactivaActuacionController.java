package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ActuacionResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ErrorBody;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.GenerarActuacionRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.GenerarDocumentoRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.HonorariosResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PlantillaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PreviewDocumentoRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PublicarDocumentoRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ResultadoHttp;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.SolicitudResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.VariablesDocumentoResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionService.DocumentoGenerado;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionService.GenerarResultado;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaGenerationService.Descarga;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaGenerationService.Preview;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coactivas")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaActuacionController {

  private final CoactivaActuacionService service;
  private final CoactivaGenerationService generacion;

  public CoactivaActuacionController(
      CoactivaActuacionService service, CoactivaGenerationService generacion) {
    this.service = service;
    this.generacion = generacion;
  }

  @GetMapping("/expedientes/{id}/plantillas")
  public List<PlantillaItem> plantillas(@PathVariable UUID id, @RequestParam String etapa) {
    return service.plantillas(id, etapa);
  }

  @PostMapping("/expedientes/{id}/generar-documento")
  public ResponseEntity<byte[]> generarDocumento(
      @PathVariable UUID id, @Valid @RequestBody GenerarDocumentoRequest request) {
    DocumentoGenerado doc =
        service.generarDocumento(id, request.plantillaId(), request.variables(), request.formato());
    return ResponseEntity.status(201)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.nombre() + "\"")
        .header("X-Actuacion-Id", doc.actuacionId().toString())
        .contentType(MediaType.parseMediaType(doc.mime()))
        .body(doc.bytes());
  }

  @GetMapping("/expedientes/{id}/actuaciones/{tipo}/variables")
  public VariablesDocumentoResponse variables(@PathVariable UUID id, @PathVariable String tipo) {
    return generacion.variables(id, tipo);
  }

  @PostMapping(
      value = "/expedientes/{id}/actuaciones/{tipo}/preview",
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> preview(
      @PathVariable UUID id,
      @PathVariable String tipo,
      @RequestBody(required = false) PreviewDocumentoRequest request) {
    Preview preview = generacion.previsualizar(id, tipo, request);
    return ResponseEntity.ok()
        .header("X-Draft-Id", preview.draftId().toString())
        .header(
            "X-Variables-Pendientes",
            String.valueOf(preview.variables().variablesPendientes().size()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"actuacion-preview.pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(preview.pdf());
  }

  @PostMapping("/expedientes/{id}/actuaciones/{tipo}/listo")
  public ActuacionResponse publicar(
      @PathVariable UUID id,
      @PathVariable String tipo,
      @RequestBody(required = false) PublicarDocumentoRequest request) {
    return generacion.publicar(id, tipo, request);
  }

  @GetMapping("/actuaciones/{draftId}/download")
  public ResponseEntity<byte[]> descargar(@PathVariable UUID draftId) {
    Descarga doc = generacion.descargar(draftId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.nombre() + "\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .body(doc.bytes());
  }

  @PostMapping("/expedientes/{id}/actuaciones")
  public ResponseEntity<ActuacionResponse> generar(
      @PathVariable UUID id,
      @Valid @RequestBody GenerarActuacionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    GenerarResultado resultado =
        service.generar(
            id, request.plantillaId(), idempotencyKey, Boolean.TRUE.equals(request.permitirIncompleto()));
    return ResponseEntity.status(resultado.creada() ? 201 : 200).body(resultado.body());
  }

  @PostMapping("/actuaciones/{id}/firma")
  public ResponseEntity<ErrorBody> firmar(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ResultadoHttp resultado = service.firmar(id, idempotencyKey);
    return ResponseEntity.status(resultado.http()).body(new ErrorBody(resultado.code(), resultado.message()));
  }

  @GetMapping("/expedientes/{id}/medidas-cautelares")
  public List<MedidaItem> medidas(@PathVariable UUID id) {
    return service.medidas(id);
  }

  @PostMapping("/expedientes/{id}/medidas-cautelares")
  public ResponseEntity<MedidaItem> crearMedida(@PathVariable UUID id, @Valid @RequestBody MedidaRequest request) {
    return ResponseEntity.status(201).body(service.crearMedida(id, request));
  }

  @GetMapping("/expedientes/{id}/honorarios")
  public HonorariosResponse honorarios(@PathVariable UUID id) {
    return service.honorarios(id);
  }

  @PostMapping("/expedientes/{id}/solicitudes-carga")
  public ResponseEntity<SolicitudResponse> solicitarCarga(
      @PathVariable UUID id,
      @RequestBody(required = false) Map<String, String> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    String nota = body == null ? null : body.get("nota");
    return ResponseEntity.status(201).body(service.solicitarCarga(id, nota, idempotencyKey));
  }

  @PostMapping("/expedientes/{id}/traslado")
  public ResponseEntity<SolicitudResponse> traslado(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return ResponseEntity.status(201).body(service.correrTraslado(id, idempotencyKey));
  }

  @PostMapping("/expedientes/{id}/datadoc/sync")
  public ResponseEntity<ErrorBody> dataDoc(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ResultadoHttp resultado = service.sincronizarDataDoc(id, idempotencyKey);
    return ResponseEntity.status(resultado.http()).body(new ErrorBody(resultado.code(), resultado.message()));
  }
}
