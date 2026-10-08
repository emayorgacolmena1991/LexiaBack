package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService.Ejecucion;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.TitleObservation;
import com.lexia.api.modules.expedientes.escrituracion.TitleObservationRepository;
import com.lexia.api.modules.expedientes.escrituracion.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.WritingFileRepository;

/**
 * POST /api/v1/expedientes/{id}/validar-ia — devuelve el dictamen persistido por analizar-ia; solo
 * llama al LLM si el expediente aún no tiene estudio.
 */
@Service
public class ValidacionIaService {

  private static final Logger LOG = LoggerFactory.getLogger(ValidacionIaService.class);
  private static final Pattern OBS_PREFIX = Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+[^:]+:\\s*");

  private final OcrSessionCacheService cache;
  private final ExpedienteCompletoLlmService llm;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;

  public ValidacionIaService(
      OcrSessionCacheService cache,
      ExpedienteCompletoLlmService llm,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations) {
    this.cache = cache;
    this.llm = llm;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
  }

  public ResultadoCotejoDTO validarExpediente(String idExpediente) {
    if (!StringUtils.hasText(idExpediente)) {
      throw ApiException.badRequest("idExpediente requerido.");
    }
    String id = idExpediente.trim();
    ResultadoCotejoDTO persistido = desdeEstudioPersistido(id);
    if (persistido != null) {
      LOG.info("validar-ia id={} desde title_study estado={}", id, persistido.estado());
      return persistido;
    }
    String content = OcrExpedienteTexto.paraClaude(cache, List.of(id));
    if (!StringUtils.hasText(content)) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para idExpediente=" + id);
    }

    ProductContext ctx = resolveProductContext(id);
    Ejecucion ejecucion = llm.ejecutar(content, ctx.productCode(), ctx.canton());
    ExtraccionExpedienteCompleto ext = ejecucion.extraccion();
    if (ext == null || ext.payload() == null || ext.payload().dictamen() == null) {
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
    ResultadoCotejoDTO resultado = aCotejo(ext.payload().dictamen());
    LOG.info(
        "validar-ia id={} product={} estado={}", id, ctx.productCode(), resultado.estado());
    return resultado;
  }

  private ResultadoCotejoDTO desdeEstudioPersistido(String idExpediente) {
    UUID caseId;
    UUID tenantId;
    try {
      caseId = UUID.fromString(idExpediente);
      tenantId = AuthContext.require().tenantId();
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      return null;
    }
    return writingFiles
        .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .flatMap(
            wf ->
                titleStudies.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(
                    wf.getId(), tenantId))
        .filter(
            s -> StringUtils.hasText(s.getStatus()) && !"PENDING".equalsIgnoreCase(s.getStatus()))
        .map(
            study -> {
              List<Observacion> obs = new ArrayList<>();
              for (TitleObservation row :
                  titleObservations.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(
                      study.getId(), tenantId)) {
                String detail = row.getDetail() == null ? "" : row.getDetail().trim();
                obs.add(new Observacion(null, null, OBS_PREFIX.matcher(detail).replaceFirst("")));
              }
              return aCotejo(new Dictamen(study.getStatus(), study.getSummary(), obs));
            })
        .orElse(null);
  }

  private static ResultadoCotejoDTO aCotejo(Dictamen dictamen) {
    String estado = dictamen.estado() == null ? "" : dictamen.estado().trim().toUpperCase();
    boolean ok = "APPROVED".equals(estado);
    boolean rechazo = "REJECTED".equals(estado);
    List<String> mensajes = new ArrayList<>();
    for (Observacion obs : dictamen.observaciones()) {
      if (obs != null && StringUtils.hasText(obs.mensaje())) {
        mensajes.add(obs.mensaje().trim());
      }
    }
    String semaforo = ok ? "APROBADO" : rechazo ? "RECHAZADO" : "ADVERTENCIA";
    return new ResultadoCotejoDTO(
        ok,
        ok,
        mensajes,
        StringUtils.hasText(dictamen.resumen()) ? dictamen.resumen() : "",
        semaforo);
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

  public static String rebuildConsolidado(List<OcrFileResult> results) {
    StringBuilder sb = new StringBuilder();
    for (OcrFileResult r : results) {
      if (r == null || !r.legible()) {
        continue;
      }
      String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento().trim() : "DOCUMENTO";
      sb.append(tipo).append(":\n:\n: ");
      sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
      sb.append("\n\n");
    }
    return sb.toString().trim();
  }

  private record ProductContext(String productCode, String canton) {}
}
