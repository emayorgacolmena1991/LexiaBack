package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCotejo;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.WritingFileRepository;

/** POST /api/v1/expedientes/{id}/validar-ia — cotejo notarial Claude sobre OCR consolidado. */
@Service
public class ValidacionIaService {

  private static final Logger LOG = LoggerFactory.getLogger(ValidacionIaService.class);

  private final OcrSessionCacheService cache;
  private final AnalisisDocumentoService analisis;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;

  public ValidacionIaService(
      OcrSessionCacheService cache,
      AnalisisDocumentoService analisis,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles) {
    this.cache = cache;
    this.analisis = analisis;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
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

    ProductContext ctx = resolveProductContext(id);
    ExtraccionCotejo ext = analisis.cotejarExpediente(content, ctx.productCode(), ctx.canton());
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
        "validar-ia id={} product={} estado={} obs={}",
        id,
        ctx.productCode(),
        ext.resultado().estado(),
        ext.resultado().observaciones().size());
    return ext.resultado();
  }

  private ProductContext resolveProductContext(String idExpediente) {
    try {
      UUID caseId = UUID.fromString(idExpediente);
      UUID tenantId = AuthContext.require().tenantId();
      return legalCases
          .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
          .map(
              lc -> {
                var wf =
                    writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId);
                String product = lc.getProductCode();
                if (!StringUtils.hasText(product)) {
                  product = wf.map(WritingFile::getProductCode).filter(StringUtils::hasText).orElse(null);
                }
                String canton =
                    wf.map(
                            w ->
                                StringUtils.hasText(w.getCanton())
                                    ? w.getCanton()
                                    : w.getMunicipality())
                        .filter(StringUtils::hasText)
                        .orElse(null);
                return new ProductContext(product, canton);
              })
          .orElse(new ProductContext(null, null));
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      return new ProductContext(null, null);
    }
  }

  private record ProductContext(String productCode, String canton) {}

  public static String rebuildConsolidado(List<OcrFileResult> results) {
    StringBuilder sb = new StringBuilder();
    for (OcrFileResult r : results) {
      if (r == null || !r.legible()) {
        continue;
      }
      String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento().trim() : "DOCUMENTO";
      // Formato nativo CotejoMotor.parseCache: "TIPO:\n:\n: texto"
      sb.append(tipo).append(":\n:\n: ");
      sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
      sb.append("\n\n");
    }
    return sb.toString().trim();
  }
}
