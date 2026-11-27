package com.lexia.api.modules.expedientes.requisitos;

import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.expedientes.requisitos.CondicionesCasoService.CondicionesCasoDTO;
import com.lexia.api.modules.expedientes.requisitos.CondicionesCasoService.DecisionCondicion;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Condiciones del caso (borrador o expediente). Id de otro tenant → 404. Leer con
 * {@code expedientes:caso:leer}; decidir y detectar con {@code expedientes:caso:escribir}.
 */
@RestController
@RequestMapping("/api/v1/expedientes/{idExpediente}/condiciones")
public class CondicionesCasoController {

  private final CondicionesCasoService service;
  private final ObjectProvider<AuthorizationService> authorization;

  public CondicionesCasoController(
      CondicionesCasoService service, ObjectProvider<AuthorizationService> authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  public record ActualizarCondicionesRequest(
      @NotNull @Size(max = 20) List<@Valid DecisionCondicion> decisiones) {}

  @GetMapping
  public CondicionesCasoDTO listar(@PathVariable String idExpediente) {
    PermisoExpediente.leer(authorization);
    return service.listar(idExpediente);
  }

  /** Confirma, descarta o agrega (CONFIRMAR de una condición sin fila) condiciones. */
  @PutMapping
  public CondicionesCasoDTO actualizar(
      @PathVariable String idExpediente, @Valid @RequestBody ActualizarCondicionesRequest body) {
    PermisoExpediente.escribir(authorization);
    return service.actualizar(idExpediente, body.decisiones());
  }

  /** La IA propone condiciones desde el OCR ya extraído (wizard tras el OCR; ficha a pedido). */
  @PostMapping("/detectar")
  public CondicionesCasoDTO detectar(@PathVariable String idExpediente) {
    PermisoExpediente.escribir(authorization);
    return service.detectar(idExpediente);
  }
}
