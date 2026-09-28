package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService.Ejecucion;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Inmueble;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto.DocOcr;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Una llamada LLM por expediente: OCR Azure ya guardado → texto {@code <documento id>} → Claude.
 * Sin unir PDFs.
 */
@Service
public class IaAnalysisService {

  private static final Logger LOG = LoggerFactory.getLogger(IaAnalysisService.class);
  private static final Set<String> GRUPOS = Set.of("comprador", "vendedor", "inmueble");
  private static final Pattern OBS =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$");

  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final ExtractedDataRepository extractedData;
  private final DocumentoTextoOcrRepository documentoTextoOcr;
  private final OcrSessionCacheService ocrCache;
  private final ExpedienteCompletoLlmService llm;
  private final AuthorizationService authorization;

  public IaAnalysisService(
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      ExtractedDataRepository extractedData,
      DocumentoTextoOcrRepository documentoTextoOcr,
      OcrSessionCacheService ocrCache,
      ExpedienteCompletoLlmService llm,
      AuthorizationService authorization) {
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.extractedData = extractedData;
    this.documentoTextoOcr = documentoTextoOcr;
    this.ocrCache = ocrCache;
    this.llm = llm;
    this.authorization = authorization;
  }

  @Transactional
  public ProcesarExpedienteCompletoResult analizarExpedienteConPromptProducto(
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

    boolean force = request != null && Boolean.TRUE.equals(request.forceReanalysis());
    if (!force && writingFile != null) {
      ProcesarExpedienteCompletoResult cached =
          loadCached(expedienteId, tenantId, writingFile, productCode);
      if (cached != null) {
        return cached;
      }
    }

    String canton = resolveCanton(writingFile);
    String ocrText = loadOcrMarcado(legalCase, request);
    Ejecucion ejecucion = llm.ejecutar(ocrText, productCode, canton, force);
    ExtraccionExpedienteCompleto ext = ejecucion.extraccion();
    if (ext == null || ext.payload() == null || "ERROR".equals(ext.estado())) {
      throw ApiException.badRequest(
          ext != null && StringUtils.hasText(ext.motivo())
              ? ext.motivo()
              : "No se obtuvo resultado de análisis IA.");
    }

    ProcesarExpedienteCompletoResult response =
        new ProcesarExpedienteCompletoResult(
            expedienteId,
            productCode,
            ejecucion.promptKey(),
            ext.payload().datosExtraidos(),
            ext.payload().dictamen());

    persistExtracted(tenantId, expedienteId, response.datosExtraidos());
    if (writingFile != null) {
      persistStudy(tenantId, writingFile, response.dictamen());
    } else {
      LOG.info(
          "analizar-ia id={} sin writing_file: dictamen no persistido en title_study",
          expedienteId);
    }

    LOG.info(
        "analizar-ia id={} product={} key={} cache={} estado={}",
        expedienteId,
        productCode,
        ejecucion.promptKey(),
        ejecucion.desdeCache(),
        response.dictamen() == null ? null : response.dictamen().estado());
    return response;
  }

  private ProcesarExpedienteCompletoResult loadCached(
      UUID expedienteId, UUID tenantId, WritingFile writingFile, String productCode) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingFile.getId(), tenantId)
        .filter(s -> StringUtils.hasText(s.getStatus()) && !"PENDING".equalsIgnoreCase(s.getStatus()))
        .map(
            study -> {
              List<Observacion> obs = new ArrayList<>();
              for (TitleObservation row :
                  titleObservations.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(
                      study.getId(), tenantId)) {
                obs.add(parseObs(row.getDetail()));
              }
              return new ProcesarExpedienteCompletoResult(
                  expedienteId,
                  productCode,
                  null,
                  loadDatos(expedienteId, tenantId),
                  new Dictamen(study.getStatus(), study.getSummary(), obs));
            })
        .orElse(null);
  }

  private DatosExtraidos loadDatos(UUID expedienteId, UUID tenantId) {
    Map<String, String> values = new LinkedHashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(expedienteId, tenantId)) {
      if (row.getFieldLabel() != null) {
        values.put(row.getFieldLabel(), row.getFieldValue());
      }
    }
    if (values.isEmpty()) {
      return new DatosExtraidos(null, null, null);
    }
    return new DatosExtraidos(
        new Persona(
            values.get("comprador.nombres"),
            values.get("comprador.cedula"),
            values.get("comprador.estadoCivil")),
        new Persona(
            values.get("vendedor.nombres"),
            values.get("vendedor.cedula"),
            values.get("vendedor.estadoCivil")),
        new Inmueble(
            values.get("inmueble.claveCatastral"),
            values.get("inmueble.linderos"),
            parseDouble(values.get("inmueble.avaluo"))));
  }

  private void persistExtracted(UUID tenantId, UUID caseId, DatosExtraidos datos) {
    extractedData.deleteByCaseIdAndTenantIdAndFieldGroupIn(caseId, tenantId, GRUPOS);
    if (datos == null) {
      return;
    }
    savePersona(tenantId, caseId, "comprador", datos.comprador());
    savePersona(tenantId, caseId, "vendedor", datos.vendedor());
    Inmueble inmueble = datos.inmueble();
    if (inmueble == null) {
      return;
    }
    saveCampo(tenantId, caseId, "inmueble", "claveCatastral", inmueble.claveCatastral());
    saveCampo(tenantId, caseId, "inmueble", "linderos", inmueble.linderos());
    if (inmueble.avaluo() != null) {
      saveCampo(tenantId, caseId, "inmueble", "avaluo", inmueble.avaluo().toString());
    }
  }

  private void savePersona(UUID tenantId, UUID caseId, String grupo, Persona persona) {
    if (persona == null) {
      return;
    }
    saveCampo(tenantId, caseId, grupo, "nombres", persona.nombres());
    saveCampo(tenantId, caseId, grupo, "cedula", persona.cedula());
    saveCampo(tenantId, caseId, grupo, "estadoCivil", persona.estadoCivil());
  }

  private void saveCampo(
      UUID tenantId, UUID caseId, String grupo, String campo, String valor) {
    if (!StringUtils.hasText(valor)) {
      return;
    }
    extractedData.save(
        ExtractedData.create(tenantId, caseId, grupo + "." + campo, valor.trim(), grupo));
  }

  private void persistStudy(UUID tenantId, WritingFile writingFile, Dictamen dictamen) {
    TitleStudy study =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(
                writingFile.getId(), tenantId)
            .orElseGet(() -> titleStudies.save(TitleStudy.create(tenantId, writingFile.getId())));
    String estado = dictamen == null || !StringUtils.hasText(dictamen.estado())
        ? "WITH_OBSERVATIONS"
        : dictamen.estado().trim();
    String resumen = dictamen == null ? "" : dictamen.resumen();
    study.applyResult(estado, resumen);
    titleStudies.save(study);
    titleObservations.deleteByTitleStudyIdAndTenantId(study.getId(), tenantId);
    if (dictamen == null) {
      return;
    }
    for (Observacion item : dictamen.observaciones()) {
      if (item == null || !StringUtils.hasText(item.mensaje())) {
        continue;
      }
      String detail =
          "["
              + (item.severidad() == null ? "MEDIUM" : item.severidad())
              + "] "
              + (item.codigo() == null ? "OBS" : item.codigo())
              + ": "
              + item.mensaje().trim();
      titleObservations.save(TitleObservation.create(tenantId, study.getId(), detail));
    }
  }

  /**
   * OCR por archivo (caché de sesión o {@code documento_texto_ocr}). No une binarios.
   */
  String loadOcrMarcado(LegalCase legalCase, AnalysisRequestDTO request) {
    List<String> candidates = new ArrayList<>();
    if (request != null && StringUtils.hasText(request.sessionId())) {
      candidates.add(request.sessionId().trim());
    }
    if (StringUtils.hasText(legalCase.getCode())) {
      candidates.add(legalCase.getCode().trim());
    }
    candidates.add(legalCase.getId().toString());

    String marcado = OcrExpedienteTexto.paraClaude(ocrCache, candidates);
    if (!StringUtils.hasText(marcado)) {
      marcado = ocrDesdeDb(candidates);
    }
    if (!StringUtils.hasText(marcado)) {
      throw ApiException.badRequest(
          "Sin texto OCR para analizar. Ejecuta Azure OCR por archivo antes del estudio IA."
              + " sessionHint="
              + (request != null ? request.sessionId() : null)
              + " case="
              + legalCase.getId());
    }
    LOG.info("analizar-ia OCR caseId={} chars={}", legalCase.getId(), marcado.length());
    return marcado;
  }

  private String ocrDesdeDb(List<String> ids) {
    for (String id : ids) {
      if (!StringUtils.hasText(id)) {
        continue;
      }
      List<DocumentoTextoOcr> rows = documentoTextoOcr.findByIdExpedienteOrderByCreatedAtAsc(id);
      if (rows == null || rows.isEmpty()) {
        continue;
      }
      List<DocOcr> docs = new ArrayList<>();
      for (DocumentoTextoOcr row : rows) {
        if (row == null || !StringUtils.hasText(row.getTextoOcr())) {
          continue;
        }
        docs.add(new DocOcr(row.getIdDocumento(), row.getTipoDocumento(), null, row.getTextoOcr()));
      }
      String marcado = OcrExpedienteTexto.documentos(docs);
      if (StringUtils.hasText(marcado)) {
        return marcado;
      }
    }
    return "";
  }

  private static Observacion parseObs(String detail) {
    String raw = detail == null ? "" : detail.trim();
    Matcher m = OBS.matcher(raw);
    if (m.matches()) {
      return new Observacion(m.group(2).trim(), m.group(1), m.group(3).trim());
    }
    return new Observacion("OBS", "MEDIUM", raw);
  }

  private static Double parseDouble(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return Double.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
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
}
