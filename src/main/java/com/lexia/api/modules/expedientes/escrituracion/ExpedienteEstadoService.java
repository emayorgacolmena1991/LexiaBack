package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoMinutaBorrador;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.DatosBiessStore;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Inmueble;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore.DocumentoExtraidoDTO;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Lee cotejo, captura BIESS y borrador de minuta ya persistidos. No llama al LLM. */
@Service
public class ExpedienteEstadoService {

  private static final Pattern OBS =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$");

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final MinutaDraftRepository minutaDrafts;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final ExtractedDataRepository extractedData;
  private final DatosBiessStore datosBiess;
  private final DocumentosExtraidosStore documentos;
  private final MinutaGenerationService minutas;

  public ExpedienteEstadoService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      MinutaDraftRepository minutaDrafts,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      ExtractedDataRepository extractedData,
      DatosBiessStore datosBiess,
      DocumentosExtraidosStore documentos,
      MinutaGenerationService minutas) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.minutaDrafts = minutaDrafts;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.extractedData = extractedData;
    this.datosBiess = datosBiess;
    this.documentos = documentos;
    this.minutas = minutas;
  }

  @Transactional(readOnly = true)
  public EstadoMinutaBorrador hidratar(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    List<DocumentoExtraidoDTO> docs = documentos.cargar(caseId, tenantId);
    DatosExtraidos extraidos = loadDatos(caseId, tenantId);

    WritingFile file =
        writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    Dictamen dictamen = null;
    MinutaViviendaData minuta = null;
    UUID draftId = null;
    boolean hasDraft = false;
    if (file != null) {
      dictamen = loadDictamen(file.getId(), tenantId);
      var draft =
          minutaDrafts.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(
              file.getId(), tenantId);
      if (draft.isPresent()) {
        hasDraft = true;
        draftId = draft.get().getId();
        minuta = minutas.leerDatos(draft.get());
      }
    }

    DatosBiessMinuta biess = datosBiess.cargar(caseId, tenantId);
    if (biess == null) {
      biess = minuta == null ? DatosBiessMinuta.empty() : DatosBiessMinuta.from(minuta);
    }
    return new EstadoMinutaBorrador(
        caseId, biess, extraidos, docs, dictamen, minuta, hasDraft, draftId);
  }

  private DatosExtraidos loadDatos(UUID caseId, UUID tenantId) {
    Map<String, String> values = new LinkedHashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(caseId, tenantId)) {
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

  private Dictamen loadDictamen(UUID writingFileId, UUID tenantId) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingFileId, tenantId)
        .map(
            study -> {
              List<Observacion> obs = new ArrayList<>();
              for (TitleObservation row :
                  titleObservations.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(
                      study.getId(), tenantId)) {
                obs.add(parseObs(row.getDetail()));
              }
              return new Dictamen(study.getStatus(), study.getSummary(), obs);
            })
        .orElse(null);
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
}
