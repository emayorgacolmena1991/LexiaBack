package com.lexia.api.modules.actos;

import com.lexia.api.modules.actos.ActosDtos.ActoNotarialListadoDTO;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/actos-notariales")
public class ActoNotarialController {

  private final ActoNotarialService actoNotarialService;

  public ActoNotarialController(ActoNotarialService actoNotarialService) {
    this.actoNotarialService = actoNotarialService;
  }

  @GetMapping
  public ResponseEntity<List<ActoNotarialListadoDTO>> listar() {
    return ResponseEntity.ok(actoNotarialService.listarActos());
  }

  @GetMapping("/{idActo}/requisitos")
  public ResponseEntity<ActoNotarialRespuestaDTO> requisitos(@PathVariable String idActo) {
    return ResponseEntity.ok(actoNotarialService.obtenerRequisitosPorActo(idActo));
  }
}
