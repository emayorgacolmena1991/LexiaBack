package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchDocument;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResultItem;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidatedGetResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ReuploadResponse;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pantalla 2: Azure OCR + calidad (sin Gemini/Claude). Resultados en caché de sesión.
 * Batch: paralelo vía {@code ocrExecutor} (S0).
 */
@Service
public class OcrAzureBatchService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrAzureBatchService.class);

  private final AzureCalidadDocumentoService layoutService;
  private final CargaDocumentoService cargaDocumentoService;
  private final OcrSessionCacheService cache;
  private final Executor ocrExecutor;
  private final PdfTextoNativo pdfNativo;
  private final DocumentoCacheStore documentoCache;

  public OcrAzureBatchService(
      AzureCalidadDocumentoService layoutService,
      CargaDocumentoService cargaDocumentoService,
      OcrSessionCacheService cache,
      @Qualifier("ocrExecutor") Executor ocrExecutor,
      PdfTextoNativo pdfNativo,
      DocumentoCacheStore documentoCache) {
    this.layoutService = layoutService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.cache = cache;
    this.ocrExecutor = ocrExecutor;
    this.pdfNativo = pdfNativo;
    this.documentoCache = documentoCache;
  }

  public AnalyzeBatchResponse analyzeBatch(AnalyzeBatchRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    if (request.documents() == null || request.documents().isEmpty()) {
      throw ApiException.badRequest("documents requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    String sessionId = request.sessionId().trim();
    List<CompletableFuture<AnalyzeBatchResultItem>> futures = new ArrayList<>();

    for (AnalyzeBatchDocument doc : request.documents()) {
      if (doc == null || !StringUtils.hasText(doc.fileId())) {
        throw ApiException.badRequest("fileId requerido en cada documento.");
      }
      AnalyzeBatchDocument snapshot = doc;
      futures.add(
          CompletableFuture.supplyAsync(
              () -> procesarDocumentoBatch(sessionId, snapshot), ocrExecutor));
    }

    List<AnalyzeBatchResultItem> results = new ArrayList<>(futures.size());
    boolean anyIllegible = false;
    boolean anyError = false;

    for (CompletableFuture<AnalyzeBatchResultItem> future : futures) {
      try {
        AnalyzeBatchResultItem item = future.join();
        results.add(item);
        if (!item.legible()) {
          anyIllegible = true;
        }
        if (StringUtils.hasText(item.motivo()) && item.motivo().startsWith("Error Azure OCR:")) {
          anyError = true;
        }
      } catch (CompletionException ce) {
        Throwable cause = ce.getCause() == null ? ce : ce.getCause();
        if (cause instanceof ApiException apiEx) {
          throw apiEx;
        }
        throw new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "OCR_BATCH_FAILED",
            "Fallo OCR batch: " + safe(cause instanceof Exception e ? e : new Exception(cause)));
      }
    }

    String status =
        anyError
            ? "COMPLETED_WITH_ERRORS"
            : (anyIllegible ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
    return new AnalyzeBatchResponse(sessionId, status, results);
  }

  private AnalyzeBatchResultItem procesarDocumentoBatch(
      String sessionId, AnalyzeBatchDocument doc) {
    String fileId = doc.fileId().trim();
    String tipo =
        StringUtils.hasText(doc.tipoDocumento()) ? doc.tipoDocumento().trim() : null;
    try {
      StoredDoc stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
      if (StringUtils.hasText(tipo) && !tipo.equals(stored.codigoTipoDocumento())) {
        cargaDocumentoService.actualizarTipoDocumentoLibre(sessionId, fileId, tipo);
        stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
      }
      String tipoFinal =
          StringUtils.hasText(stored.codigoTipoDocumento())
              ? stored.codigoTipoDocumento()
              : (tipo == null ? "DOCUMENTO" : tipo);
      return procesarArchivo(sessionId, stored, tipoFinal);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("OCR batch fileId={} err={}", fileId, e.getMessage());
      AnalyzeBatchResultItem fail =
          new AnalyzeBatchResultItem(
              fileId,
              tipo == null ? "DOCUMENTO" : tipo,
              false,
              "Error Azure OCR: " + safe(e),
              0.0,
              "");
      cache.putResult(
          sessionId,
          new OcrFileResult(
              fail.fileId(),
              doc.fileName(),
              fail.tipoDocumento(),
              false,
              fail.motivo(),
              0.0,
              ""));
      return fail;
    }
  }

  public AnalyzeSingleResponse analyzeSingle(AnalyzeSingleRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    if (!StringUtils.hasText(request.fileId())) {
      throw ApiException.badRequest("fileId requerido.");
    }

    String sessionId = request.sessionId().trim();
    String fileId = request.fileId().trim();
    String tipo =
        StringUtils.hasText(request.tipoDocumento()) ? request.tipoDocumento().trim() : null;

    StoredDoc stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
    if (StringUtils.hasText(tipo) && !tipo.equals(stored.codigoTipoDocumento())) {
      cargaDocumentoService.actualizarTipoDocumentoLibre(sessionId, fileId, tipo);
      stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
    }
    String tipoFinal =
        StringUtils.hasText(stored.codigoTipoDocumento())
            ? stored.codigoTipoDocumento()
            : (tipo == null ? "DOCUMENTO" : tipo);

    long inicio = System.nanoTime();
    String hash = stored.bytes() == null ? null : DocumentoCacheStore.sha256(stored.bytes());
    var cacheado = hash == null ? java.util.Optional.<DocumentoCacheStore.Hit>empty() : documentoCache.buscar(hash, DocumentoCacheStore.VERSION_OCR);
    if (cacheado.isPresent() && StringUtils.hasText(cacheado.get().textoOcr())) {
      return respuestaDe(fileId, stored.nombreOriginal(), tipoFinal, true, "Recuperado de caché.", 0.99,
          cacheado.get().textoOcr(), "LEGIBLE", "CACHE", ms(inicio));
    }
    var nativo = pdfNativo.extraerSiDenso(stored.bytes());
    if (nativo.isPresent()) {
      if (hash != null) {
        documentoCache.guardar(hash, DocumentoCacheStore.VERSION_OCR, "PDF_NATIVO", nativo.get(), null);
      }
      return respuestaDe(fileId, stored.nombreOriginal(), tipoFinal, true, "PDF con texto nativo.", 0.95,
          nativo.get(), "LEGIBLE", "PDF_NATIVO", ms(inicio));
    }

    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }
    AnalyzeBatchResultItem item = procesarArchivo(sessionId, stored, tipoFinal);
    String estado = item.legible() ? "LEGIBLE" : "REVISAR";
    if (hash != null && StringUtils.hasText(item.textoExtraido())) {
      documentoCache.guardar(hash, DocumentoCacheStore.VERSION_OCR, "AZURE", item.textoExtraido(), null);
    }
    return respuestaDe(
        item.fileId(),
        stored.nombreOriginal(),
        item.tipoDocumento(),
        item.legible(),
        item.motivo(),
        item.scoreConfianza(),
        item.textoExtraido(),
        estado,
        "AZURE",
        ms(inicio));
  }

  private static int ms(long inicioNano) {
    return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - inicioNano) / 1_000_000L);
  }

  private static AnalyzeSingleResponse respuestaDe(
      String fileId,
      String fileName,
      String tipo,
      boolean legible,
      String mensaje,
      double confianza,
      String texto,
      String estado,
      String origen,
      int duracionMs) {
    return new AnalyzeSingleResponse(fileId, fileName, tipo, legible, mensaje, confianza, texto, estado, origen, duracionMs);
  }

  public ReuploadResponse reupload(
      String sessionId, String fileId, String tipoDocumento, MultipartFile file) {
    if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(fileId)) {
      throw ApiException.badRequest("sessionId y fileId requeridos.");
    }
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("file requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    StoredDoc replaced =
        cargaDocumentoService.reemplazarArchivoOcr(
            sessionId.trim(), fileId.trim(), file, tipoDocumento);
    String tipo =
        StringUtils.hasText(replaced.codigoTipoDocumento())
            ? replaced.codigoTipoDocumento()
            : (StringUtils.hasText(tipoDocumento) ? tipoDocumento.trim() : "DOCUMENTO");

    AnalyzeBatchResultItem item = procesarArchivo(sessionId.trim(), replaced, tipo);
    return new ReuploadResponse(
        item.fileId(),
        replaced.nombreOriginal(),
        item.tipoDocumento(),
        item.legible(),
        item.scoreConfianza(),
        item.textoExtraido(),
        item.motivo());
  }

  public ConsolidateResponse consolidate(ConsolidateRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String sessionId = request.sessionId().trim();
    String content = request.consolidatedContent() == null ? "" : request.consolidatedContent();
    String previo = cache.getConsolidated(sessionId);
    if (!StringUtils.hasText(previo)) {
      previo = cargaDocumentoService.textoConsolidado(sessionId);
    }
    if (OcrExpedienteTexto.contarDocumentos(previo) > OcrExpedienteTexto.contarDocumentos(content)) {
      content = previo;
    }
    cache.putConsolidated(sessionId, content);
    var principal = AuthContext.get();
    if (StringUtils.hasText(content)) {
      cargaDocumentoService.persistirTextoConsolidado(
          sessionId, principal == null ? null : principal.tenantId(), content);
      LOG.info("[OCR OK] Texto consolidado y persistido en app.documento_texto_ocr");
    }
    return new ConsolidateResponse(true, OcrSessionCacheService.cacheKey(sessionId));
  }

  public ConsolidatedGetResponse getConsolidated(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    String content = cache.getConsolidated(id);
    if (content == null) {
      content = cargaDocumentoService.textoConsolidado(id);
    }
    if (content == null) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para sessionId=" + id);
    }
    return new ConsolidatedGetResponse(id, OcrSessionCacheService.cacheKey(id), content);
  }

  private AnalyzeBatchResultItem procesarArchivo(
      String sessionId, StoredDoc stored, String tipoDocumento) {
    LayoutExtractResult layout =
        layoutService.evaluarYExtraer(stored.bytes(), stored.mimeType());
    CalidadDocumentoResultado q = layout.calidad();
    String texto = layout.texto() == null ? "" : layout.texto();

    AnalyzeBatchResultItem item =
        new AnalyzeBatchResultItem(
            stored.idDocumento(),
            tipoDocumento,
            q.legible(),
            q.legible() ? null : q.mensaje(),
            q.promedioConfianza(),
            texto);

    cache.putResult(
        sessionId,
        new OcrFileResult(
            item.fileId(),
            stored.nombreOriginal(),
            item.tipoDocumento(),
            item.legible(),
            item.motivo(),
            item.scoreConfianza(),
            item.textoExtraido()));
    int confianza =
        (int)
            Math.round(
                item.scoreConfianza() <= 1 ? item.scoreConfianza() * 100 : item.scoreConfianza());
    cargaDocumentoService.guardarResultadoOcr(
        sessionId,
        new com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO(
            item.fileId(),
            stored.nombreOriginal(),
            item.tipoDocumento(),
            item.textoExtraido(),
            null,
            item.legible() ? "LEGIBLE" : "REVISAR",
            item.motivo(),
            confianza));
    return item;
  }

  private static String safe(Exception e) {
    String m = e.getMessage();
    if (!StringUtils.hasText(m)) {
      return e.getClass().getSimpleName();
    }
    return m.length() > 180 ? m.substring(0, 180) + "…" : m;
  }
}
