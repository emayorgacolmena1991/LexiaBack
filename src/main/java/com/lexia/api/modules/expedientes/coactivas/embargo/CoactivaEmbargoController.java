package com.lexia.api.modules.expedientes.coactivas.embargo;

import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.DelegadoResumen;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.ExpedienteResponse;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.GuardarRequest;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.LoteEntregado;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.LoteResumen;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistroItem;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistrosResponse;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoService.Descarga;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coactivas")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaEmbargoController {

  private static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final CoactivaEmbargoService service;

  public CoactivaEmbargoController(CoactivaEmbargoService service) {
    this.service = service;
  }

  @GetMapping("/expedientes/{id}/embargo-excel")
  public ExpedienteResponse expediente(@PathVariable UUID id) {
    return service.expediente(id);
  }

  @PutMapping("/expedientes/{id}/embargo-excel")
  public RegistroItem guardar(@PathVariable UUID id, @Valid @RequestBody GuardarRequest request) {
    return service.guardar(id, request);
  }

  @GetMapping("/embargo-excel/delegados")
  public List<DelegadoResumen> delegados() {
    return service.delegados();
  }

  // Lecturas por delegado: con `delegadoId` explícito o el delegado de `expedienteId`.

  @GetMapping("/embargo-excel/registros")
  public RegistrosResponse registros(
      @RequestParam(required = false) UUID expedienteId, @RequestParam(required = false) UUID delegadoId) {
    return service.registros(expedienteId, delegadoId, null);
  }

  @GetMapping("/embargo-excel/descargar")
  public ResponseEntity<byte[]> descargar(
      @RequestParam(required = false) UUID expedienteId, @RequestParam(required = false) UUID delegadoId) {
    return xlsx(service.descargar(expedienteId, delegadoId, null));
  }

  @GetMapping("/embargo-excel/lotes/entregados")
  public List<LoteEntregado> entregados(
      @RequestParam(required = false) UUID expedienteId, @RequestParam(required = false) UUID delegadoId) {
    return service.entregados(expedienteId, delegadoId);
  }

  @GetMapping("/embargo-excel/lotes/{loteId}/registros")
  public RegistrosResponse registrosLote(
      @PathVariable UUID loteId,
      @RequestParam(required = false) UUID expedienteId,
      @RequestParam(required = false) UUID delegadoId) {
    return service.registros(expedienteId, delegadoId, loteId);
  }

  @GetMapping("/embargo-excel/lotes/{loteId}/descargar")
  public ResponseEntity<byte[]> descargarLote(
      @PathVariable UUID loteId,
      @RequestParam(required = false) UUID expedienteId,
      @RequestParam(required = false) UUID delegadoId) {
    return xlsx(service.descargar(expedienteId, delegadoId, loteId));
  }

  private static ResponseEntity<byte[]> xlsx(Descarga descarga) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(descarga.nombre(), StandardCharsets.UTF_8).build().toString())
        .contentType(XLSX)
        .body(descarga.bytes());
  }

  @PostMapping("/embargo-excel/lotes/{loteId}/entregar")
  public LoteResumen entregar(@PathVariable UUID loteId) {
    return service.entregar(loteId);
  }
}
