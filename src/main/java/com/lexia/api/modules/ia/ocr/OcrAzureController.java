package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.expedientes.PermisoExpediente;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleResponse;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E01: lote Azure OCR + calidad (sin LLM). Opción B: analyze-single. */
@RestController
@RequestMapping("/api/v1/ocr/azure")
public class OcrAzureController {

  private final OcrAzureBatchService batchService;
  private final ObjectProvider<AuthorizationService> authorization;

  public OcrAzureController(
      OcrAzureBatchService batchService, ObjectProvider<AuthorizationService> authorization) {
    this.batchService = batchService;
    this.authorization = authorization;
  }

  @PostMapping("/analyze-batch")
  public AnalyzeBatchResponse analyzeBatch(@Valid @RequestBody AnalyzeBatchRequest request) {
    PermisoExpediente.escribir(authorization);
    return batchService.analyzeBatch(request);
  }

  @PostMapping("/analyze-single")
  public AnalyzeSingleResponse analyzeSingle(@Valid @RequestBody AnalyzeSingleRequest request) {
    PermisoExpediente.escribir(authorization);
    return batchService.analyzeSingle(request);
  }
}
