package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisResultDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.ObservationItem;
import com.lexia.api.modules.expedientes.reglas.ValidacionIaService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCotejo;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import com.lexia.api.modules.ia.prompt.ProductPromptMapRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * POST /expedientes/{id}/escrituracion/analizar-ia — estudio IA con prompt del producto
 * (TICKET-DEV-704).
 */
@Service
public class IaAnalysisService {

  private static final Logger LOG = LoggerFactory.getLogger(IaAnalysisService.class);
  private static final String DEFAULT_PROMPT_KEY = "PROMPT_DEFAULT";

  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final ProductPromptMapRepository productPromptMapRepository;
  private final OcrSessionCacheService ocrCache;
  private final AnalisisDocumentoService analisis;
  private final AuthorizationService authorization;

  public IaAnalysisService(
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      ProductPromptMapRepository productPromptMapRepository,
      OcrSessionCacheService ocrCache,
      AnalisisDocumentoService analisis,
      AuthorizationService authorization) {
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.productPromptMapRepository = productPromptMapRepository;
    this.ocrCache = ocrCache;
    this.analisis = analisis;
    this.authorization = authorization;
  }

  @Transactional
  public AnalysisResultDTO analizarExpedienteConPromptProducto(
      UUID expedienteId, AnalysisRequestDTO request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();

    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    WritingFile writingFile =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId)
            .orElse(null);

    String productCode = resolveProductCode(legalCase, writingFile);
    if (!StringUtils.hasText(productCode)) {
      throw ApiException.badRequest("El expediente no tiene un producto asignado.");
    }

    String promptKey =
        productPromptMapRepository
            .findPromptKeyByProductCode(productCode.trim())
            .filter(StringUtils::hasText)
            .orElse(DEFAULT_PROMPT_KEY);

    boolean force =
        request == null
            || request.forceReanalysis() == null
            || Boolean.TRUE.equals(request.forceReanalysis());

    if (!force && writingFile != null) {
      AnalysisResultDTO cached = loadCached(expedienteId, tenantId, writingFile, productCode, promptKey);
      if (cached != null) {
        return cached;
      }
    }

    String canton = resolveCanton(writingFile);
    String ocrText = loadOcrConsolidated(legalCase, request);
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para análisis IA.");
    }

    ExtraccionCotejo ext = analisis.cotejarExpediente(ocrText, productCode, canton);
    if (ext == null || ext.resultado() == null) {
      throw ApiException.badRequest(
          ext != null && StringUtils.hasText(ext.motivo())
              ? ext.motivo()
              : "No se obtuvo resultado de análisis IA.");
    }
    if ("ERROR".equals(ext.estado())) {
      LOG.warn("analizar-ia id={} error={}", expedienteId, ext.motivo());
      throw ApiException.badRequest(
          StringUtils.hasText(ext.motivo()) ? ext.motivo() : "Error en análisis IA.");
    }

    ResultadoCotejoDTO resultado = ext.resultado();
    AnalysisResultDTO response = toResult(expedienteId, productCode, promptKey, resultado);

    if (writingFile != null) {
      persistStudy(tenantId, writingFile, response);
    } else {
      LOG.info(
          "analizar-ia id={} sin writing_file: resultado no persistido en title_study",
          expedienteId);
    }

    LOG.info(
        "analizar-ia id={} product={} key={} status={} obs={}",
        expedienteId,
        productCode,
        promptKey,
        response.status(),
        response.observations().size());
    return response;
  }

  private AnalysisResultDTO loadCached(
      UUID expedienteId,
      UUID tenantId,
      WritingFile writingFile,
      String productCode,
      String promptKey) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingFile.getId(), tenantId)
        .filter(s -> StringUtils.hasText(s.getStatus()) && !"PENDING".equalsIgnoreCase(s.getStatus()))
        .map(
            study -> {
              List<ObservationItem> obs =
                  titleObservations
                      .findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(study.getId(), tenantId)
                      .stream()
                      .map(
                          o ->
                              new ObservationItem(
                                  "OBS_CACHED",
                                  "MEDIUM",
                                  o.getDetail() == null ? "" : o.getDetail()))
                      .toList();
              return new AnalysisResultDTO(
                  expedienteId,
                  productCode,
                  promptKey,
                  study.getStatus(),
                  study.getSummary(),
                  obs,
                  Map.of());
            })
        .orElse(null);
  }

  private void persistStudy(UUID tenantId, WritingFile writingFile, AnalysisResultDTO result) {
    TitleStudy study =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(
                writingFile.getId(), tenantId)
            .orElseGet(() -> titleStudies.save(TitleStudy.create(tenantId, writingFile.getId())));
    study.applyResult(result.status(), result.summary());
    titleStudies.save(study);

    for (ObservationItem item : result.observations()) {
      if (item != null && StringUtils.hasText(item.message())) {
        String detail =
            "["
                + (item.severity() == null ? "MEDIUM" : item.severity())
                + "] "
                + (item.code() == null ? "OBS" : item.code())
                + ": "
                + item.message().trim();
        titleObservations.save(TitleObservation.create(tenantId, study.getId(), detail));
      }
    }
  }

  /**
   * OCR consolidado post-paso 3 (async): textos de todos los docs unidos en caché de sesión.
   * La UI guarda bajo sessionId del borrador ({@code EXP-YYYY-NNNNN}), no bajo el UUID del caso.
   */
  private String loadOcrConsolidated(LegalCase legalCase, AnalysisRequestDTO request) {
    List<String> candidates = new ArrayList<>();
    if (request != null && StringUtils.hasText(request.sessionId())) {
      candidates.add(request.sessionId().trim());
    }
    if (StringUtils.hasText(legalCase.getCode())) {
      candidates.add(legalCase.getCode().trim());
    }
    candidates.add(legalCase.getId().toString());

    String content = null;
    String usedSession = null;
    for (String id : candidates) {
      if (!StringUtils.hasText(id)) {
        continue;
      }
      String cached = ocrCache.getConsolidated(id);
      List<OcrFileResult> results = ocrCache.listResults(id);
      if (cached == null && (results == null || results.isEmpty())) {
        continue;
      }
      if (!StringUtils.hasText(cached) && results != null && !results.isEmpty()) {
        cached = ValidacionIaService.rebuildConsolidado(results);
      }
      if (StringUtils.hasText(cached)) {
        content = cached;
        usedSession = id;
        break;
      }
    }

    if (!StringUtils.hasText(content)) {
      throw ApiException.badRequest(
          "Sin texto OCR consolidado para analizar. Completa el paso 3 (OCR) y consolida los"
              + " documentos antes de ejecutar el estudio IA. sessionHint="
              + (request != null ? request.sessionId() : null)
              + " case="
              + legalCase.getId());
    }
    LOG.info(
        "analizar-ia OCR session={} caseId={} chars={}",
        usedSession,
        legalCase.getId(),
        content.length());
    return content;
  }

  private static String resolveProductCode(LegalCase legalCase, WritingFile writingFile) {
    if (StringUtils.hasText(legalCase.getProductCode())) {
      return legalCase.getProductCode().trim();
    }
    if (writingFile != null && StringUtils.hasText(writingFile.getProductCode())) {
      return writingFile.getProductCode().trim();
    }
    return null;
  }

  private static String resolveCanton(WritingFile writingFile) {
    if (writingFile == null) {
      return "GUAYAQUIL";
    }
    if (StringUtils.hasText(writingFile.getCanton())) {
      return writingFile.getCanton().trim();
    }
    if (StringUtils.hasText(writingFile.getMunicipality())) {
      return writingFile.getMunicipality().trim();
    }
    return "GUAYAQUIL";
  }

  static AnalysisResultDTO toResult(
      UUID expedienteId, String productCode, String promptKey, ResultadoCotejoDTO r) {
    List<ObservationItem> observations = new ArrayList<>();
    int i = 1;
    for (String msg : r.observaciones()) {
      if (!StringUtils.hasText(msg)) {
        continue;
      }
      String severity =
          "RECHAZADO".equalsIgnoreCase(r.estado())
              ? "HIGH"
              : "ADVERTENCIA".equalsIgnoreCase(r.estado()) ? "MEDIUM" : "LOW";
      observations.add(new ObservationItem("OBS_" + i++, severity, msg.trim()));
    }

    String status = mapStatus(r, observations);
    String summary =
        StringUtils.hasText(r.resumenValidacion())
            ? r.resumenValidacion()
            : defaultSummary(r, observations);

    Map<String, String> extracted = new LinkedHashMap<>();
    extracted.put("coincidePersona", String.valueOf(r.coincidePersona()));
    extracted.put("coincideInmueble", String.valueOf(r.coincideInmueble()));

    return new AnalysisResultDTO(
        expedienteId, productCode, promptKey, status, summary, observations, extracted);
  }

  private static String mapStatus(ResultadoCotejoDTO r, List<ObservationItem> observations) {
    String estado = r.estado() == null ? "" : r.estado().trim().toUpperCase(Locale.ROOT);
    if ("APROBADO".equals(estado) && observations.isEmpty()) {
      return "APPROVED";
    }
    if ("RECHAZADO".equals(estado)) {
      return "REJECTED";
    }
    if (!observations.isEmpty() || "ADVERTENCIA".equals(estado)) {
      return "WITH_OBSERVATIONS";
    }
    return StringUtils.hasText(estado) ? estado : "WITH_OBSERVATIONS";
  }

  private static String defaultSummary(ResultadoCotejoDTO r, List<ObservationItem> observations) {
    if (observations.isEmpty() && r.coincidePersona() && r.coincideInmueble()) {
      return "Cotejo OK: identidad e inmueble coinciden.";
    }
    if (!observations.isEmpty()) {
      return "Análisis con " + observations.size() + " observación(es).";
    }
    return "Análisis completado.";
  }
}
