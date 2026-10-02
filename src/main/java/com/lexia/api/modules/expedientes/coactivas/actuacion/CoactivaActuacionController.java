package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ActuacionResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ErrorBody;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.GenerarActuacionRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.HonorariosResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.MedidaRequest;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.PlantillaItem;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.ResultadoHttp;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionDtos.SolicitudResponse;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaActuacionService.GenerarResultado;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

  public CoactivaActuacionController(CoactivaActuacionService service) {
    this.service = service;
  }

  @GetMapping("/expedientes/{id}/plantillas")
  public List<PlantillaItem> plantillas(@PathVariable UUID id, @RequestParam String etapa) {
    return service.plantillas(id, etapa);
  }

  @PostMapping("/expedientes/{id}/actuaciones")
  public ResponseEntity<ActuacionResponse> generar(
      @PathVariable UUID id,
      @Valid @RequestBody GenerarActuacionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    GenerarResultado resultado = service.generar(id, request.plantillaId(), idempotencyKey);
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
