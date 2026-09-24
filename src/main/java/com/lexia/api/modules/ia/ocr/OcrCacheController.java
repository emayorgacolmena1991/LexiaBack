package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidatedGetResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E03/E04: caché consolidada de textos OCR por sesión. */
@RestController
@RequestMapping("/api/v1/cache")
public class OcrCacheController {

  private final OcrAzureBatchService batchService;

  public OcrCacheController(OcrAzureBatchService batchService) {
    this.batchService = batchService;
  }

  @PostMapping("/consolidate-extracted-text")
  public ConsolidateResponse consolidate(@Valid @RequestBody ConsolidateRequest request) {
    return batchService.consolidate(request);
  }

  @GetMapping("/consolidate-extracted-text/{sessionId}")
  public ConsolidatedGetResponse get(@PathVariable String sessionId) {
    return batchService.getConsolidated(sessionId);
  }
}
