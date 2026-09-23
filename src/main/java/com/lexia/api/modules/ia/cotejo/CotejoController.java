package com.lexia.api.modules.ia.cotejo;

import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paso 4. Consume la caché E04 ({@code GET /api/v1/cache/consolidate-extracted-text/{sessionId}})
 * y devuelve el cotejo. No dispara Azure OCR.
 */
@RestController
@RequestMapping("/api/v1/cache")
public class CotejoController {

  private final CotejoDatosService cotejoDatosService;

  public CotejoController(CotejoDatosService cotejoDatosService) {
    this.cotejoDatosService = cotejoDatosService;
  }

  @GetMapping("/cotejo/{sessionId}")
  public CotejoResponse cotejar(@PathVariable String sessionId) {
    return cotejoDatosService.cotejar(sessionId);
  }
}
