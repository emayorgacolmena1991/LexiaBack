package com.lexia.api.modules.expedientes.escrituracion.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
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
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
  private static final Set<String> GRUPOS = Set.of("comprador", "vendedor", "inmueble");
  private static final Pattern OBS =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$");
  /** Líneas que la IA debe emitir en resumen para dar de baja una obs en regularización. */
  private static final Pattern RESUELTA_LINE =
      Pattern.compile("(?im)^\\s*\\[RESUELTA]\\s*([^:\\n]+?)\\s*(?::\\s*(.*))?$");

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
      ObjectMapper objectMapper) {
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
                .orElse(null);

    String productCode =
        legalCase != null ? resolveProductCode(legalCase, writingFile) : text(borrador.getProductCode());
    if (!StringUtils.hasText(productCode)) {
      throw ApiException.badRequest("El expediente no tiene un producto asignado.");
    }

    boolean force = request != null && Boolean.TRUE.equals(request.forceReanalysis());
    boolean enRegularizacion = writingFile != null && writingFile.isFlagBloqueoReenvio();
    // Bloqueo solo sin force: en regularización se permite reanalizar a mano tras re-cargar docs.
    if (enRegularizacion && !force) {
      ProcesarExpedienteCompletoResult cached =
          loadCached(expedienteId, tenantId, writingFile, productCode);
      if (cached != null) {
        return cached;
      }
      throw new ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REGULARIZACION_BLOQUEADA",
          "El expediente está en regularización. Usa reanálisis forzado tras re-cargar documentos.");
    }

    if (!force && borrador != null) {
      ProcesarExpedienteCompletoResult cachedDraft = readDraftStudy(borrador);
      if (cachedDraft != null) {
        return cachedDraft;
      }
    }
    if (!force && writingFile != null) {
      ProcesarExpedienteCompletoResult cached =
          loadCached(expedienteId, tenantId, writingFile, productCode);
      if (cached != null) {
        return cached;
      }
    }

    String canton = legalCase != null ? resolveCanton(writingFile) : cantonOf(borrador);
    List<ObservacionesCotejo.Entrada> pendientesPrevias =
        enRegularizacion && force
            ? pendientesDeWriting(writingFile, tenantId)
            : List.of();
    String ocrText =
        force
            ? ocrParaReanalisis(
                expedienteId, legalCase == null ? null : legalCase.getCode(), request)
            : loadOcrMarcado(
                expedienteId, legalCase == null ? null : legalCase.getCode(), request);
    if (!pendientesPrevias.isEmpty()) {
      ocrText = ocrText + contextoRegularizacion(pendientesPrevias);
    }
    Ejecucion ejecucion = llm.ejecutar(ocrText, productCode, canton);
    ExtraccionExpedienteCompleto ext = ejecucion.extraccion();
    if (ext == null || ext.payload() == null || "ERROR".equals(ext.estado())) {
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "LLM_FAILED",
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
    Dictamen dictamen = ext.payload().dictamen();
    if (!pendientesPrevias.isEmpty()) {
      dictamen = fusionarDictamenRegularizacion(pendientesPrevias, dictamen);
    }
    ProcesarExpedienteCompletoResult response =
        new ProcesarExpedienteCompletoResult(
            expedienteId,
            productCode,
            ejecucion.promptKey(),
            documentosExtraidos,
            ext.payload().datosExtraidos(),
            dictamen);

    if (legalCase != null) {
      persistExtracted(tenantId, expedienteId, response.datosExtraidos());
    }
    if (writingFile != null) {
      persistStudy(tenantId, writingFile, response.dictamen());
    } else if (borrador != null) {
      persistDraftStudy(borrador, response);
    } else {
      LOG.info(
          "analizar-ia id={} sin writing_file: dictamen no persistido en title_study",
          expedienteId);
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

  /** Reanálisis: OCR por archivo/sesión si existe; si no, consolidado del worker. */
  private String ocrParaReanalisis(UUID expedienteId, String caseCode, AnalysisRequestDTO request) {
    try {
      return loadOcrMarcado(expedienteId, caseCode, request);
    } catch (ApiException ignored) {
      return ocrConsolidado(expedienteId);
    }
  }

  /** Reanálisis: solo el consolidado ya guardado por el worker. */
  private String ocrConsolidado(UUID expedienteId) {
    String texto =
        documentoTextoOcr
            .findByIdExpedienteAndIdDocumento(expedienteId.toString(), "consolidado")
            .map(DocumentoTextoOcr::getTextoOcr)
            .orElse("");
    if (!StringUtils.hasText(texto)) {
      throw ApiException.badRequest("Sin texto consolidado en documento_texto_ocr.");
    }
    LOG.info("analizar-ia OCR consolidado caseId={} chars={}", expedienteId, texto.length());
    return OcrExpedienteTexto.envolverPlano(texto);
  }

  private List<ObservacionesCotejo.Entrada> pendientesDeWriting(WritingFile writingFile, UUID tenantId) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingFile.getId(), tenantId)
        .map(study -> ObservacionesCotejo.pendientes(titleObservations, tenantId, study.getId()))
        .orElse(List.of())
        .stream()
        .map(item -> new ObservacionesCotejo.Entrada(item.code(), item.severidad(), item.mensaje()))
        .toList();
  }

  /**
   * Inyecta las observaciones abiertas para que el LLM las evalúe una a una. Subir un PDF no
   * implica resolución: debe confirmar con {@code [RESUELTA] codigo} en el resumen.
   */
  private static String contextoRegularizacion(List<ObservacionesCotejo.Entrada> pendientes) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n\n<regularizacion_pendiente>\n");
    sb.append(
        """
        El expediente está en regularización. Evalúa CADA observación abierta con la evidencia OCR.
        Reglas:
        - Si SIGUE abierta: inclúyela en dictamen.observaciones con el MISMO codigo.
        - Si la evidencia la RESUELVE: NO la listes en observaciones y añade en dictamen.resumen una línea:
          [RESUELTA] CODIGO: motivo breve
        - Subir un archivo NO implica que esté resuelta. Si el documento no aporta a esa observación, manténla.
        Ítems abiertos:
        """);
    int i = 1;
    for (ObservacionesCotejo.Entrada e : pendientes) {
      String code = StringUtils.hasText(e.code()) ? e.code().trim() : "OBS";
      String sev = StringUtils.hasText(e.severidad()) ? e.severidad().trim() : "MEDIUM";
      sb.append(i++)
          .append(". [")
          .append(sev)
          .append("] ")
          .append(code)
          .append(": ")
          .append(e.mensaje() == null ? "" : e.mensaje().trim())
          .append('\n');
    }
    sb.append("</regularizacion_pendiente>\n");
    return sb.toString();
  }

  /**
   * Conserva observaciones previas si la IA las omitió sin marcar {@code [RESUELTA]}. Evita que un
   * APPROVED vacío (o un PDF irrelevante) borre la lista abierta.
   */
  private static Dictamen fusionarDictamenRegularizacion(
      List<ObservacionesCotejo.Entrada> previas, Dictamen dictamen) {
    List<Observacion> fromIa =
        dictamen == null || dictamen.observaciones() == null
            ? List.of()
            : dictamen.observaciones();
    Set<String> resueltas = parseResueltas(dictamen == null ? null : dictamen.resumen());
    Set<String> keysIa = new LinkedHashSet<>();
    Set<String> codesIa = new LinkedHashSet<>();
    List<Observacion> merged = new ArrayList<>();
    for (Observacion item : fromIa) {
      if (item == null || !StringUtils.hasText(item.mensaje())) {
        continue;
      }
      String code = StringUtils.hasText(item.codigo()) ? item.codigo().trim() : "OBS";
      keysIa.add(ObservacionesCotejo.clave(code, item.mensaje()));
      codesIa.add(code.toUpperCase(Locale.ROOT));
      merged.add(item);
    }
    for (ObservacionesCotejo.Entrada prev : previas) {
      if (prev == null || !StringUtils.hasText(prev.mensaje())) {
        continue;
      }
      String code = StringUtils.hasText(prev.code()) ? prev.code().trim() : "OBS";
      String key = ObservacionesCotejo.clave(code, prev.mensaje());
      String codeUp = code.toUpperCase(Locale.ROOT);
      if (keysIa.contains(key) || codesIa.contains(codeUp)) {
        continue;
      }
      if (resueltas.contains(codeUp) || resueltas.contains(key)) {
        continue;
      }
      // Sin confirmación explícita: sigue pendiente.
      merged.add(new Observacion(code, prev.severidad(), prev.mensaje()));
    }
    String estado =
        dictamen == null || !StringUtils.hasText(dictamen.estado())
            ? "WITH_OBSERVATIONS"
            : dictamen.estado().trim();
    if (!merged.isEmpty() && "APPROVED".equalsIgnoreCase(estado)) {
      estado = "WITH_OBSERVATIONS";
    }
    if (merged.isEmpty() && resueltas.isEmpty() && !previas.isEmpty()) {
      // APPROVED vacío sin [RESUELTA]: no confiar — reponer todas.
      for (ObservacionesCotejo.Entrada prev : previas) {
        if (prev != null && StringUtils.hasText(prev.mensaje())) {
          merged.add(
              new Observacion(
                  StringUtils.hasText(prev.code()) ? prev.code() : "OBS",
                  prev.severidad(),
                  prev.mensaje()));
        }
      }
      estado = "WITH_OBSERVATIONS";
    }
    String resumen = dictamen == null ? "" : dictamen.resumen();
    return new Dictamen(estado, resumen, List.copyOf(merged));
  }

  private static Set<String> parseResueltas(String resumen) {
    Set<String> out = new LinkedHashSet<>();
    if (!StringUtils.hasText(resumen)) {
      return out;
    }
    Matcher m = RESUELTA_LINE.matcher(resumen);
    while (m.find()) {
      String code = m.group(1) == null ? "" : m.group(1).trim();
      if (StringUtils.hasText(code)) {
        out.add(code.toUpperCase(Locale.ROOT));
      }
    }
    return out;
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
    LOG.info("analizar-ia OCR caseId={} chars={}", expedienteId, marcado.length());
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
