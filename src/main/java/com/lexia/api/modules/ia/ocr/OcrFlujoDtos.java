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

  /** Opción B — un archivo. */
  public record AnalyzeSingleRequest(String sessionId, String fileId, String tipoDocumento) {}

  public record AnalyzeSingleResponse(
      String fileId,
      String fileName,
      String tipoDocumento,
      boolean legible,
      String mensaje,
      Double confianza,
      String textoExtraido,
      String estado) {}

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

  /** GET /api/v1/cache/cotejo/{sessionId} — E04 cotejo cross-doc (alineado a CotejoDto FE). */
  public record CotejoFuente(String documento, String valor) {}

  public record CotejoComparacion(
      String campo,
      String label,
      String estado,
      String relacion,
      String valor,
      /** Explicación legible del resultado (por qué coincide / discrepa / revisar). */
      String motivo,
      List<CotejoFuente> fuentes) {}

  /**
   * Resumen ejecutivo para la vista principal del paso 4 (máx. ~5 grupos).
   * El detalle campo-a-campo vive en {@code comparaciones} del grupo, con
   * {@code fuentes[]} por documento y {@code motivo} explicativo.
   */
  public record CotejoGrupo(
      String id,
      String label,
      String estado,
      String resumen,
      List<CotejoComparacion> comparaciones) {}

  public record CotejoResumen(
      int total,
      int coinciden,
      int diferencias,
      int noEncontrados,
      Integer reglasAplicadas,
      Boolean observacion,
      /** Texto de observación general decidido por el backend (no hardcodear en FE). */
      String observacionGeneral) {}

  public record CotejoResponse(
      String sessionId,
      CotejoResumen resumen,
      /** Lista plana (compatibilidad / consumo detallado). */
      List<CotejoComparacion> comparaciones,
      /** Vista principal: categorías funcionales (Identidad, Inmueble, Linderos, …). */
      List<CotejoGrupo> grupos) {}
}
