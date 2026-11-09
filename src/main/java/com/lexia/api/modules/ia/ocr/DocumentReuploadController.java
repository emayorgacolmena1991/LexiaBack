package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ReuploadResponse;
import com.lexia.api.modules.identity.AuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** E02: re-subida individual LoadDocuments. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentReuploadController {

  private final OcrAzureBatchService batchService;
  private final ObjectProvider<AuthorizationService> authorization;

  public DocumentReuploadController(
      OcrAzureBatchService batchService, ObjectProvider<AuthorizationService> authorization) {
    this.batchService = batchService;
    this.authorization = authorization;
  }

  @PostMapping(value = "/reupload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ReuploadResponse reupload(
      @RequestParam("sessionId") String sessionId,
      @RequestParam("fileId") String fileId,
      @RequestParam(value = "tipoDocumento", required = false) String tipoDocumento,
      @RequestParam("file") MultipartFile file) {
    authorization.ifAvailable(service -> service.requirePermission("expedientes:caso:escribir"));
    return batchService.reupload(sessionId, fileId, tipoDocumento, file);
  }
}
