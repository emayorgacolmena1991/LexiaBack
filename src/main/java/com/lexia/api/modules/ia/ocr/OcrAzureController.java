package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E01: lote Azure OCR + calidad (sin LLM). */
@RestController
@RequestMapping("/api/v1/ocr/azure")
public class OcrAzureController {

  private final OcrAzureBatchService batchService;

  public OcrAzureController(OcrAzureBatchService batchService) {
    this.batchService = batchService;
  }

  @PostMapping("/analyze-batch")
  public AnalyzeBatchResponse analyzeBatch(@Valid @RequestBody AnalyzeBatchRequest request) {
    return batchService.analyzeBatch(request);
  }
}
