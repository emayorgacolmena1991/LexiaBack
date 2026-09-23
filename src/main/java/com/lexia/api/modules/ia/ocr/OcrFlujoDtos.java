package com.lexia.api.modules.ia.ocr;

import java.util.List;

/** DTOs E01–E04 flujo OCR Azure + caché (sin LLM). */
public final class OcrFlujoDtos {

  private OcrFlujoDtos() {}

  public record AnalyzeBatchRequest(String sessionId, List<AnalyzeBatchDocument> documents) {}

  public record AnalyzeBatchDocument(
      String fileId, String fileName, String tipoDocumento, String fileUrl) {}

  public record AnalyzeBatchResponse(
      String sessionId, String status, List<AnalyzeBatchResultItem> results) {}

  public record AnalyzeBatchResultItem(
      String fileId,
      String tipoDocumento,
      boolean legible,
      String motivo,
      double scoreConfianza,
      String textoExtraido) {}

  public record ReuploadResponse(
      String fileId,
      String fileName,
      String tipoDocumento,
      boolean legible,
      double scoreConfianza,
      String textoExtraido,
      String motivo) {}

  public record ConsolidateRequest(String sessionId, String consolidatedContent) {}

  public record ConsolidateResponse(boolean success, String cacheKey) {}

  public record ConsolidatedGetResponse(String sessionId, String cacheKey, String consolidatedContent) {}
}
