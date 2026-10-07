package com.lexia.api.modules.expedientes.coactivas.embargo;

import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.ExpedienteResponse;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.GuardarRequest;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.LoteResumen;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistroItem;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoDtos.RegistrosResponse;
import com.lexia.api.modules.expedientes.coactivas.embargo.CoactivaEmbargoService.Descarga;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
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

  @GetMapping("/embargo-excel/registros")
  public RegistrosResponse registros() {
    return service.registros();
  }

  @GetMapping("/embargo-excel/descargar")
  public ResponseEntity<byte[]> descargar() {
    Descarga descarga = service.descargar();
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
