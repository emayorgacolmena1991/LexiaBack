package com.lexia.api.modules.expedientes.cierre;

import com.lexia.api.common.api.Descargas;
import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.Archivo;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.CorreoAfiliadoDTO;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.DatosCierreDTO;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.DatosCierreRequest;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.EstadoCartaDTO;
import com.lexia.api.modules.expedientes.cierre.CierreExpedienteService.PagosCartaRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;

/**
 * Datos de cierre, carta de financiamiento, informe legal final y correo al afiliado. Leer con
 * {@code expedientes:caso:leer}; guardar con {@code expedientes:caso:escribir}. Otro tenant → 404.
 * Los archivos se descargan como adjunto ({@code octet-stream} + nosniff).
 */
@RestController
@RequestMapping("/api/v1/expedientes/{idExpediente}")
public class CierreExpedienteController {

  private final CierreExpedienteService service;
  private final ObjectProvider<AuthorizationService> authorization;

  public CierreExpedienteController(
      CierreExpedienteService service, ObjectProvider<AuthorizationService> authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  @GetMapping("/cierre")
  public DatosCierreDTO obtener(@PathVariable UUID idExpediente) {
    PermisoExpediente.leer(authorization);
    return service.obtener(idExpediente);
  }

  @PutMapping("/cierre")
  public DatosCierreDTO guardar(
      @PathVariable UUID idExpediente, @Valid @RequestBody DatosCierreRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.guardar(idExpediente, body);
  }

  @GetMapping("/cierre/estado")
  public EstadoCartaDTO estado(@PathVariable UUID idExpediente) {
    PermisoExpediente.leer(authorization);
    return service.estadoCarta(idExpediente);
  }

  @PutMapping("/carta-financiamiento/pagos")
  public EstadoCartaDTO guardarPagos(
      @PathVariable UUID idExpediente, @Valid @RequestBody PagosCartaRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.guardarPagos(idExpediente, body);
  }

  @GetMapping("/carta-financiamiento")
  public ResponseEntity<byte[]> carta(
      @PathVariable UUID idExpediente,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          java.time.LocalDate fecha) {
    PermisoExpediente.leer(authorization);
    return adjunto(service.carta(idExpediente, fecha));
  }

  @GetMapping("/informe-legal")
  public ResponseEntity<byte[]> informe(@PathVariable UUID idExpediente) {
    PermisoExpediente.leer(authorization);
    return adjunto(service.informe(idExpediente));
  }

  @GetMapping("/correo-afiliado")
  public CorreoAfiliadoDTO correo(@PathVariable UUID idExpediente) {
    PermisoExpediente.leer(authorization);
    return service.correo(idExpediente);
  }

  private static ResponseEntity<byte[]> adjunto(Archivo archivo) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, Descargas.adjunto(archivo.nombre()))
        .header("X-Content-Type-Options", "nosniff")
        .body(archivo.bytes());
  }
}
