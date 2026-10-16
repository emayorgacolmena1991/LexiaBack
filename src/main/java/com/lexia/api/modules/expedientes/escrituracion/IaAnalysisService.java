package com.lexia.api.modules.expedientes.escrituracion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.api.Precondicion;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobQueue;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import com.lexia.api.modules.ia.llm.IaConsumoContexto;
import com.lexia.api.modules.ia.llm.IaModelRouting;
import com.lexia.api.modules.ia.ocr.OcrTextSanitizerService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService.Ejecucion;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Inmueble;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DocumentoExtraido;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto.DocOcr;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
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
 * Una llamada LLM por expediente: OCR Azure ya guardado → texto {@code <documento id>} → LLM.
 * La misma respuesta alimenta el cotejo ({@code extracted_data} grupo documento), los datos
 * consolidados y el dictamen ({@code title_study}). Sin unir PDFs.
 */
@Service
public class IaAnalysisService {

  private static final Logger LOG = LoggerFactory.getLogger(IaAnalysisService.class);
  private static final ThreadLocal<Boolean> MODO_PROFUNDO = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> PROFUNDO_PENDIENTE = new ThreadLocal<>();
  private static final Set<String> GRUPOS = Set.of("comprador", "vendedor", "inmueble");
  private static final Pattern OBS =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$");

  private final LegalCaseRepository legalCases;
  private final ExpedienteBorradorStore borradores;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final ExtractedDataRepository extractedData;
  private final DocumentoTextoOcrRepository documentoTextoOcr;
  private final OcrSessionCacheService ocrCache;
  private final ExpedienteCompletoLlmService llm;
  private final DocumentosExtraidosStore documentos;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;
  private final OcrTextSanitizerService sanitizer;
  private final IngestaJobQueue jobs;

  public IaAnalysisService(
      LegalCaseRepository legalCases,
      ExpedienteBorradorStore borradores,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      ExtractedDataRepository extractedData,
      DocumentoTextoOcrRepository documentoTextoOcr,
      OcrSessionCacheService ocrCache,
      ExpedienteCompletoLlmService llm,
      DocumentosExtraidosStore documentos,
      AuthorizationService authorization,
      ObjectMapper objectMapper,
      OcrTextSanitizerService sanitizer,
      IngestaJobQueue jobs) {
    this.legalCases = legalCases;
    this.borradores = borradores;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.extractedData = extractedData;
    this.documentoTextoOcr = documentoTextoOcr;
    this.ocrCache = ocrCache;
    this.llm = llm;
    this.documentos = documentos;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
    this.sanitizer = sanitizer;
    this.jobs = jobs;
  }

  public static boolean profundoPendiente() {
    return Boolean.TRUE.equals(PROFUNDO_PENDIENTE.get());
  }

  public static void limpiarProfundo() {
    PROFUNDO_PENDIENTE.remove();
    MODO_PROFUNDO.remove();
  }

  /** Sonnet sobre el análisis de Haiku ya guardado. No vuelve a encolar. */
  @Transactional
  public ProcesarExpedienteCompletoResult analizarProfundo(
      UUID expedienteId, AnalysisRequestDTO request) {
    MODO_PROFUNDO.set(true);
    try {
      return analizarExpedienteConPromptProducto(expedienteId, request);
    } finally {
      MODO_PROFUNDO.remove();
    }
  }

  public void exigirExpediente(UUID expedienteId) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    if (borradores.find(expedienteId, tenantId).isPresent()) {
      return;
    }
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
  }

  @Transactional
  public ProcesarExpedienteCompletoResult analizarExpedienteConPromptProducto(
      UUID expedienteId, AnalysisRequestDTO request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();

    ExpedienteBorrador borrador = borradores.find(expedienteId, tenantId).orElse(null);
    LegalCase legalCase = null;
    if (borrador != null) {
      LOG.info("analizar-ia usa borrador id={} tenant={}", expedienteId, tenantId);
    } else {
      legalCase =
          legalCases
              .findByIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId)
              .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
    }

    WritingFile writingFile =
        legalCase == null
            ? null
            : writingFiles
                .findByCaseIdAndTenantIdAndDeletedAtIsNull(expedienteId, tenantId)
                .orElseGet(() -> writingFiles.save(WritingFile.create(tenantId, expedienteId)));

    String productCode =
        legalCase != null ? resolveProductCode(legalCase, writingFile) : text(borrador.getProductCode());
    if (!StringUtils.hasText(productCode)) {
      throw ApiException.badRequest("El expediente no tiene un producto asignado.");
    }
    if (writingFile != null && !StringUtils.hasText(writingFile.getProductCode())) {
      writingFile.applyProduct(productCode, resolveCanton(writingFile), null);
      writingFiles.save(writingFile);
    }

    if (writingFile != null && writingFile.isFlagBloqueoReenvio()) {
      ProcesarExpedienteCompletoResult cached =
          loadCached(expedienteId, tenantId, writingFile, productCode);
      if (cached != null) {
        return cached;
      }
      throw new ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REGULARIZACION_BLOQUEADA",
          "El expediente está en regularización. El reanálisis queda detenido hasta marcarlo regularizado.");
    }

    boolean force = request != null && Boolean.TRUE.equals(request.forceReanalysis());
    boolean profundo = Boolean.TRUE.equals(MODO_PROFUNDO.get());
    if (!profundo && !force && borrador != null) {
      ProcesarExpedienteCompletoResult cachedDraft = readDraftStudy(borrador);
      if (cachedDraft != null) {
        return cachedDraft;
      }
    }
    if (!profundo && !force && writingFile != null) {
      ProcesarExpedienteCompletoResult cached =
          loadCached(expedienteId, tenantId, writingFile, productCode);
      if (cached != null) {
        return cached;
      }
    }

    String canton = legalCase != null ? resolveCanton(writingFile) : cantonOf(borrador);
    String ocrText =
        sanitizer.limpiar(
            loadOcrMarcado(
                expedienteId, legalCase == null ? null : legalCase.getCode(), request));
    IaConsumoContexto.setExpediente(expedienteId);
    Ejecucion ejecucion;
    try {
      ejecucion =
          profundo
              ? llm.ejecutarProfundo(ocrText, productCode, canton)
              : llm.ejecutar(ocrText, productCode, canton);
    } finally {
      IaConsumoContexto.clear();
    }
    ExtraccionExpedienteCompleto ext = ejecucion.extraccion();
    if (ext == null || ext.payload() == null || "ERROR".equals(ext.estado())) {
      throw ApiException.badRequest(
          ext != null && StringUtils.hasText(ext.motivo())
              ? ext.motivo()
              : "No se obtuvo resultado de análisis IA.");
    }

    List<DocOcr> cabeceras = OcrExpedienteTexto.cabeceras(ocrText);
    List<DocumentoExtraido> extraidosLlm = ext.payload().documentosExtraidos();
    List<DocumentoExtraidoDTO> documentosExtraidos =
        legalCase == null
            ? documentos.proyectar(extraidosLlm, cabeceras)
            : documentos.guardar(tenantId, expedienteId, extraidosLlm, cabeceras);
    ProcesarExpedienteCompletoResult response =
        new ProcesarExpedienteCompletoResult(
            expedienteId,
            productCode,
            ejecucion.promptKey(),
            documentosExtraidos,
            ext.payload().datosExtraidos(),
            ext.payload().dictamen());
    persistirDictamen(tenantId, expedienteId, ocrText, response);

    if (legalCase != null) {
      persistExtracted(tenantId, expedienteId, response.datosExtraidos());
    }
    if (writingFile != null) {
      persistStudy(tenantId, writingFile, response.dictamen(), ejecucion);
    } else if (borrador != null) {
      persistDraftStudy(borrador, response);
    } else {
      LOG.info(
          "analizar-ia id={} sin writing_file: dictamen no persistido en title_study",
          expedienteId);
    }

    boolean escalar =
        !profundo && !ejecucion.desdeCache() && IaModelRouting.necesitaPesado(ext);
    LOG.info(
        "routing-analisis caseId={} rapido=true profundo={} motivo={}",
        expedienteId,
        escalar,
        escalar ? IaModelRouting.motivo(ext) : null);
    if (escalar) {
      encolarProfundo(expedienteId, request);
    }

    LOG.info(
        "analizar-ia id={} product={} key={} documentos={} estado={}",
        expedienteId,
        productCode,
        ejecucion.promptKey(),
        documentosExtraidos.size(),
        response.dictamen() == null ? null : response.dictamen().estado());
    return response;
  }

  private void encolarProfundo(UUID expedienteId, AnalysisRequestDTO request) {
    if (jobs == null) {
      return;
    }
    AuthPrincipal principal = AuthContext.get();
    if (principal == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("expedienteId", expedienteId.toString());
    payload.put("forceReanalysis", false);
    payload.put("sessionIdOcr", request == null || request.sessionId() == null ? "" : request.sessionId());
    payload.put("userId", principal.userId() == null ? "" : principal.userId().toString());
    payload.put("sessionId", principal.sessionId() == null ? "" : principal.sessionId().toString());
    payload.put(
        "membershipId", principal.membershipId() == null ? "" : principal.membershipId().toString());
    try {
      jobs.encolar(principal.tenantId(), "IA_ANALISIS_PROFUNDO", objectMapper.writeValueAsString(payload), -1);
      PROFUNDO_PENDIENTE.set(true);
    } catch (JsonProcessingException ex) {
      LOG.warn("No se pudo encolar IA_ANALISIS_PROFUNDO caseId={}: {}", expedienteId, ex.getMessage());
    }
  }

  private ProcesarExpedienteCompletoResult readDraftStudy(ExpedienteBorrador borrador) {
    if (borrador == null || !StringUtils.hasText(borrador.getDatosExtraidos())) {
      return null;
    }
    try {
      return objectMapper.readValue(
          borrador.getDatosExtraidos(), ProcesarExpedienteCompletoResult.class);
    } catch (JsonProcessingException e) {
      LOG.warn("analizar-ia borrador id={} datos_extraidos ilegible", borrador.getId());
      return null;
    }
  }

  /** Fila fija: el cotejo la lee si la caché de sesión ya no existe. */
  private void persistirDictamen(
      UUID tenantId, UUID expedienteId, String ocrText, ProcesarExpedienteCompletoResult response) {
    try {
      DocumentoTextoOcr row =
          documentoTextoOcr
              .findByIdExpedienteAndIdDocumento(expedienteId.toString(), "dictamen")
              .orElseGet(DocumentoTextoOcr::new);
      row.setTenantId(tenantId);
      row.setIdExpediente(expedienteId.toString());
      row.setIdDocumento("dictamen");
      row.setTipoDocumento("DICTAMEN");
      if (StringUtils.hasText(ocrText)) {
        row.setTextoOcr(ocrText);
      }
      row.setAnalisisJson(objectMapper.writeValueAsString(response));
      row.setEstadoDoc("LISTO");
      documentoTextoOcr.save(row);
    } catch (JsonProcessingException e) {
      throw ApiException.badRequest("No se pudo guardar el dictamen del expediente.");
    }
  }

  private void persistDraftStudy(ExpedienteBorrador borrador, ProcesarExpedienteCompletoResult response) {
    try {
      borradores.guardarEstudio(
          borrador.getId(), borrador.getTenantId(), objectMapper.writeValueAsString(response));
    } catch (JsonProcessingException e) {
      throw ApiException.badRequest("No se pudo guardar el estudio IA del borrador.");
    }
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
                  documentos.cargar(expedienteId, tenantId),
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

  /** Cada análisis es una versión nueva de {@code title_study}; las lecturas toman la última. */
  private void persistStudy(
      UUID tenantId, WritingFile writingFile, Dictamen dictamen, Ejecucion ejecucion) {
    TitleStudy study = TitleStudy.create(tenantId, writingFile.getId());
    study.applyOrigen(ejecucion.versionPrompt(), ejecucion.modelo(), ejecucion.sha256());
    String estado = dictamen == null || !StringUtils.hasText(dictamen.estado())
        ? "WITH_OBSERVATIONS"
        : dictamen.estado().trim();
    String resumen = dictamen == null ? "" : dictamen.resumen();
    study.applyResult(estado, resumen);
    titleStudies.save(study);
    List<ObservacionesCotejo.Entrada> entradas = new java.util.ArrayList<>();
    if (dictamen != null && dictamen.observaciones() != null) {
      for (Observacion item : dictamen.observaciones()) {
        if (item == null || !StringUtils.hasText(item.mensaje())) {
          continue;
        }
        entradas.add(new ObservacionesCotejo.Entrada(item.codigo(), item.severidad(), item.mensaje()));
      }
    }
    ObservacionesCotejo.reemplazar(titleObservations, tenantId, study.getId(), entradas);
  }

  /** No encola el estudio si no hay texto. El worker reprograma si el OCR llega después. */
  public void exigirTextoOcr(UUID expedienteId, String sessionId) {
    String code =
        legalCases
            .findById(expedienteId)
            .map(LegalCase::getCode)
            .orElse("");
    try {
      loadOcrMarcado(expedienteId, code, new AnalysisRequestDTO(false, sessionId));
    } catch (ApiException ex) {
      if (ex.getMessage() != null && ex.getMessage().contains("Sin texto OCR")) {
        throw Precondicion.fallo(
            "POST analizar-ia",
            "SIN_TEXTO_OCR",
            "Debes ejecutar OCR antes del análisis IA",
            "POST /api/v1/ocr/azure/analyze-single",
            Map.of("expedienteId", expedienteId.toString()));
      }
      throw ex;
    }
  }

  /**
   * OCR por archivo (caché de sesión o {@code documento_texto_ocr}). No une binarios.
   */
  String loadOcrMarcado(UUID expedienteId, String caseCode, AnalysisRequestDTO request) {
    List<String> candidates = new ArrayList<>();
    if (request != null && StringUtils.hasText(request.sessionId())) {
      candidates.add(request.sessionId().trim());
    }
    if (StringUtils.hasText(caseCode)) {
      candidates.add(caseCode.trim());
    }
    candidates.add(expedienteId.toString());

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
              + expedienteId);
    }
    logExcluidos(expedienteId, candidates, marcado);
    LOG.info(
        "analizar-ia OCR caseId={} chars={} documentos={}",
        expedienteId,
        marcado.length(),
        OcrExpedienteTexto.contarDocumentos(marcado));
    return marcado;
  }

  private void logExcluidos(UUID expedienteId, List<String> candidates, String marcado) {
    String texto = marcado == null ? "" : marcado;
    for (String id : candidates) {
      List<OcrFileResult> results = ocrCache.listResults(id);
      if (results.isEmpty()) {
        continue;
      }
      for (OcrFileResult archivo : results) {
        if (archivo == null || !StringUtils.hasText(archivo.fileId())) {
          continue;
        }
        if (texto.contains("id=\"" + archivo.fileId().trim() + "\"")) {
          continue;
        }
        String motivo = motivoExclusion(archivo);
        LOG.info(
            "analizar-ia documento excluido docId={} motivo={} caseId={}",
            archivo.fileId(),
            motivo,
            expedienteId);
      }
      return;
    }
  }

  private static String motivoExclusion(OcrFileResult archivo) {
    if (!archivo.legible()) {
      return "SIN_OCR";
    }
    if (!StringUtils.hasText(archivo.textoExtraido())) {
      return "VACIO";
    }
    return "TIPO_NO_ELEGIBLE";
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
      String consolidado = "";
      for (DocumentoTextoOcr row : rows) {
        if (row == null || !StringUtils.hasText(row.getTextoOcr())) {
          if (row != null && !"consolidado".equals(row.getIdDocumento())) {
            LOG.info(
                "analizar-ia documento excluido docId={} motivo={}",
                row.getIdDocumento(),
                row.getTextoOcr() == null ? "SIN_OCR" : "VACIO");
          }
          continue;
        }
        if ("consolidado".equals(row.getIdDocumento())) {
          consolidado = row.getTextoOcr();
          continue;
        }
        docs.add(new DocOcr(row.getIdDocumento(), row.getTipoDocumento(), null, row.getTextoOcr()));
      }
      String marcado =
          OcrExpedienteTexto.masDocumentos(
              OcrExpedienteTexto.documentos(docs), OcrExpedienteTexto.marcarConsolidado(consolidado));
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

  private static String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String cantonOf(ExpedienteBorrador borrador) {
    if (borrador != null && StringUtils.hasText(borrador.getCanton())) {
      return borrador.getCanton().trim();
    }
    return "GUAYAQUIL";
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
