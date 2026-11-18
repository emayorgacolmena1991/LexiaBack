package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidatedGetResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.CotejoResponse;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E03/E04: caché consolidada de textos OCR + cotejo IA por sesión. */
@RestController
@RequestMapping("/api/v1/cache")
public class OcrCacheController {

  private final OcrAzureBatchService batchService;
  private final OcrCotejoService cotejoService;
  private final ObjectProvider<AuthorizationService> authorization;

  public OcrCacheController(
      OcrAzureBatchService batchService,
      OcrCotejoService cotejoService,
      ObjectProvider<AuthorizationService> authorization) {
    this.batchService = batchService;
    this.cotejoService = cotejoService;
    this.authorization = authorization;
  }

  @PostMapping("/consolidate-extracted-text")
  public ConsolidateResponse consolidate(@Valid @RequestBody ConsolidateRequest request) {
    PermisoExpediente.escribir(authorization);
    return batchService.consolidate(request);
  }

  @GetMapping("/consolidate-extracted-text/{sessionId}")
  public ConsolidatedGetResponse get(@PathVariable String sessionId) {
    PermisoExpediente.leer(authorization);
    return batchService.getConsolidated(sessionId);
  }

  /**
   * E04 — coteja con la extracción persistida por analizar-ia (sin LLM). {@code caseId} es el
   * expediente cuando difiere del borrador {@code sessionId}.
   */
  @GetMapping("/cotejo/{sessionId}")
  public CotejoResponse cotejo(
      @PathVariable String sessionId, @RequestParam(required = false) String caseId) {
    PermisoExpediente.leer(authorization);
    return cotejoService.cotejar(sessionId, caseId);
  }
}
