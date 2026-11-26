package com.lexia.api.modules.expedientes.comparecientes;

import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.expedientes.comparecientes.ComparecientesService.ComparecienteRequest;
import com.lexia.api.modules.expedientes.comparecientes.ComparecientesService.ComparecientesDTO;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comparecientes del borrador o expediente. Id de otro tenant → 404. Leer con
 * {@code expedientes:caso:leer}; editar y ligar documentos con {@code expedientes:caso:escribir}.
 */
@RestController
@RequestMapping("/api/v1/expedientes/{idExpediente}")
public class ComparecientesController {

  private final ComparecientesService service;
  private final ObjectProvider<AuthorizationService> authorization;

  public ComparecientesController(
      ComparecientesService service, ObjectProvider<AuthorizationService> authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  /** {@code comparecienteId} null desliga el documento. */
  public record LigarDocumentoRequest(UUID comparecienteId) {}

  @GetMapping("/comparecientes")
  public ComparecientesDTO listar(@PathVariable String idExpediente) {
    PermisoExpediente.leer(authorization);
    return service.listar(idExpediente);
  }

  @PostMapping("/comparecientes")
  public ComparecientesDTO crear(
      @PathVariable String idExpediente, @Valid @RequestBody ComparecienteRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.crear(idExpediente, body);
  }

  @PutMapping("/comparecientes/{id}")
  public ComparecientesDTO actualizar(
      @PathVariable String idExpediente,
      @PathVariable UUID id,
      @Valid @RequestBody ComparecienteRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.actualizar(idExpediente, id, body);
  }

  @DeleteMapping("/comparecientes/{id}")
  public ComparecientesDTO eliminar(@PathVariable String idExpediente, @PathVariable UUID id) {
    PermisoExpediente.escribir(authorization);
    return service.eliminar(idExpediente, id);
  }

  @PatchMapping("/documentos/{idDocumento}/compareciente")
  public ComparecientesDTO ligarDocumento(
      @PathVariable String idExpediente,
      @PathVariable String idDocumento,
      @RequestBody LigarDocumentoRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.ligarDocumento(idExpediente, idDocumento, body == null ? null : body.comparecienteId());
  }
}
