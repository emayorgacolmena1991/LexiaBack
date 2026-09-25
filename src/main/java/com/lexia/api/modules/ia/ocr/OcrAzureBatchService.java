package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pantalla 2: Azure OCR + calidad (sin Gemini/Claude). Resultados en caché de sesión.
 */
@Service
public class OcrAzureBatchService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrAzureBatchService.class);

  private final AzureCalidadDocumentoService layoutService;
  private final CargaDocumentoService cargaDocumentoService;
  private final OcrSessionCacheService cache;

  public OcrAzureBatchService(
      AzureCalidadDocumentoService layoutService,
      CargaDocumentoService cargaDocumentoService,
      OcrSessionCacheService cache) {
    this.layoutService = layoutService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.cache = cache;
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
    List<AnalyzeBatchResultItem> results = new ArrayList<>();
    boolean anyIllegible = false;
    boolean anyError = false;

    for (AnalyzeBatchDocument doc : request.documents()) {
      if (doc == null || !StringUtils.hasText(doc.fileId())) {
        throw ApiException.badRequest("fileId requerido en cada documento.");
      }
      String fileId = doc.fileId().trim();
      String tipo =
          StringUtils.hasText(doc.tipoDocumento())
              ? doc.tipoDocumento().trim()
              : null;

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

        AnalyzeBatchResultItem item = procesarArchivo(sessionId, stored, tipoFinal);
        results.add(item);
        if (!item.legible()) {
          anyIllegible = true;
        }
      } catch (ApiException e) {
        throw e;
      } catch (Exception e) {
        anyError = true;
        LOG.warn("OCR batch fileId={} err={}", fileId, e.getMessage());
        AnalyzeBatchResultItem fail =
            new AnalyzeBatchResultItem(
                fileId,
                tipo == null ? "DOCUMENTO" : tipo,
                false,
                "Error Azure OCR: " + safe(e),
                0.0,
                "");
        results.add(fail);
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
      }
    }

    String status =
        anyError
            ? "COMPLETED_WITH_ERRORS"
            : (anyIllegible ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
    return new AnalyzeBatchResponse(sessionId, status, results);
  }

  public AnalyzeSingleResponse analyzeSingle(AnalyzeSingleRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    if (!StringUtils.hasText(request.fileId())) {
      throw ApiException.badRequest("fileId requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
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

    AnalyzeBatchResultItem item = procesarArchivo(sessionId, stored, tipoFinal);
    String estado = item.legible() ? "LEGIBLE" : "REVISAR";
    return new AnalyzeSingleResponse(
        item.fileId(),
        stored.nombreOriginal(),
        item.tipoDocumento(),
        item.legible(),
        item.motivo(),
        item.scoreConfianza(),
        item.textoExtraido(),
        estado);
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
    cache.putConsolidated(sessionId, content);
    return new ConsolidateResponse(true, OcrSessionCacheService.cacheKey(sessionId));
  }

  public ConsolidatedGetResponse getConsolidated(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    String content = cache.getConsolidated(id);
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
