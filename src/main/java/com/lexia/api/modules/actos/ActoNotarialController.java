package com.lexia.api.modules.actos;

import com.lexia.api.modules.actos.ActosDtos.ActoNotarialListadoDTO;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/actos-notariales")
public class ActoNotarialController {

  private final ActoNotarialService actoNotarialService;
  private final ObjectProvider<AuthorizationService> authorization;

  public ActoNotarialController(
      ActoNotarialService actoNotarialService,
      ObjectProvider<AuthorizationService> authorization) {
    this.actoNotarialService = actoNotarialService;
    this.authorization = authorization;
  }

  @GetMapping
  public ResponseEntity<List<ActoNotarialListadoDTO>> listar() {
    PermisoExpediente.leer(authorization);
    return ResponseEntity.ok(actoNotarialService.listarActos());
  }

  @GetMapping("/{idActo}/requisitos")
  public ResponseEntity<ActoNotarialRespuestaDTO> requisitos(@PathVariable String idActo) {
    PermisoExpediente.leer(authorization);
    return ResponseEntity.ok(actoNotarialService.obtenerRequisitosPorActo(idActo));
  }
}
