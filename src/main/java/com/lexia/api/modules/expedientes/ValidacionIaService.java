package com.lexia.api.modules.expedientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCotejo;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** POST /api/v1/expedientes/{id}/validar-ia — cotejo notarial Claude sobre OCR consolidado. */
@Service
public class ValidacionIaService {

  private static final Logger LOG = LoggerFactory.getLogger(ValidacionIaService.class);

  private final OcrSessionCacheService cache;
  private final AnalisisDocumentoService analisis;

  public ValidacionIaService(OcrSessionCacheService cache, AnalisisDocumentoService analisis) {
    this.cache = cache;
    this.analisis = analisis;
  }

  public ResultadoCotejoDTO validarExpediente(String idExpediente) {
    if (!StringUtils.hasText(idExpediente)) {
      throw ApiException.badRequest("idExpediente requerido.");
    }
    String id = idExpediente.trim();
    String content = cache.getConsolidated(id);
    List<OcrFileResult> results = cache.listResults(id);

    if (content == null && (results == null || results.isEmpty())) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para idExpediente=" + id);
    }
    if (!StringUtils.hasText(content) && results != null && !results.isEmpty()) {
      content = rebuildConsolidado(results);
    }
    if (!StringUtils.hasText(content)) {
      throw ApiException.badRequest("Sin texto OCR consolidado para validar.");
    }
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para validación IA.");
    }

    ExtraccionCotejo ext = analisis.cotejarExpediente(content);
    if (ext == null || ext.resultado() == null) {
      throw ApiException.badRequest(
          ext != null && StringUtils.hasText(ext.motivo())
              ? ext.motivo()
              : "No se obtuvo resultado de cotejo.");
    }
    if ("ERROR".equals(ext.estado())) {
      LOG.warn("validar-ia id={} error={}", id, ext.motivo());
      throw ApiException.badRequest(
          StringUtils.hasText(ext.motivo()) ? ext.motivo() : "Error en cotejo IA.");
    }
    LOG.info(
        "validar-ia id={} estado={} obs={}",
        id,
        ext.resultado().estado(),
        ext.resultado().observaciones().size());
    return ext.resultado();
  }

  static String rebuildConsolidado(List<OcrFileResult> results) {
    StringBuilder sb = new StringBuilder();
    for (OcrFileResult r : results) {
      if (r == null || !r.legible()) {
        continue;
      }
      String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento().trim() : "DOCUMENTO";
      sb.append("=== DOCUMENTO: ").append(tipo).append(" ===\n");
      sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
      sb.append("\n\n");
    }
    return sb.toString().trim();
  }
}
