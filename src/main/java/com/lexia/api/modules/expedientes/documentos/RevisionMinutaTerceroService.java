package com.lexia.api.modules.expedientes.documentos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.ObservacionesCotejo;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservationRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudy;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import com.lexia.api.modules.ia.prompt.PromptRegistryService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * EJ-3: OCR + revisión IA de la minuta de compraventa de tercero. No aplica a
 * {@link LegalDocument#TIPO_SOPORTE_INGRESOS}.
 */
@Service
public class RevisionMinutaTerceroService {

  public static final String PROMPT_KEY = "PROMPT_REVISION_MINUTA_TERCERO";

  private static final Logger LOG = LoggerFactory.getLogger(RevisionMinutaTerceroService.class);

  private final AzureOcrService ocr;
  private final ObjectProvider<AnalisisDocumentoService> llm;
  private final PromptRegistryService prompts;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final LegalDocumentRepository documents;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate tx;

  public RevisionMinutaTerceroService(
      AzureOcrService ocr,
      ObjectProvider<AnalisisDocumentoService> llm,
      PromptRegistryService prompts,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      LegalDocumentRepository documents,
      ObjectMapper objectMapper,
      PlatformTransactionManager txManager) {
    this.ocr = ocr;
    this.llm = llm;
    this.prompts = prompts;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.documents = documents;
    this.objectMapper = objectMapper;
    this.tx = new TransactionTemplate(txManager);
  }

  /** Texto plano vía Azure Document Intelligence (PDF/imagen). */
  public String extraerTexto(byte[] bytes, String mimeType) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    if (!ocr.isConfigured()) {
      throw new IllegalStateException("Azure OCR no está configurado en este ambiente.");
    }
    String mime =
        StringUtils.hasText(mimeType) ? mimeType.trim() : "application/octet-stream";
    return ocr.extraerTexto(bytes, mime);
  }

  /**
   * Tras subir {@link LegalDocument#TIPO_MINUTA_TERCERO}: OCR → Claude/Gemini {@code
   * validarDocumento} → fusiona observaciones de este documento en {@code title_observation}.
   * Fallos de IA/OCR no revierten la carga (solo log).
   */
  public void revisarTrasSubida(
      UUID tenantId, UUID caseId, UUID documentoId, byte[] bytes, String mimeType) {
    if (tenantId == null || caseId == null || documentoId == null) {
      return;
    }
    LegalDocument doc =
        documents.findByIdAndTenantIdAndDeletedAtIsNull(documentoId, tenantId).orElse(null);
    if (doc == null
        || !caseId.equals(doc.getCaseId())
        || !LegalDocument.TIPO_MINUTA_TERCERO.equals(doc.getDocType())) {
      // SOPORTE_INGRESOS u otro tipo: no revisar.
      return;
    }
    try {
      String texto = extraerTexto(bytes, mimeType);
      if (!StringUtils.hasText(texto)) {
        LOG.warn("EJ-3 minuta tercero sin texto OCR case={} doc={}", caseId, documentoId);
        return;
      }
      AnalisisDocumentoService analisis = llm.getIfAvailable();
      if (analisis == null || !analisis.isConfigured()) {
        LOG.warn("EJ-3 minuta tercero: LLM no configurado case={}", caseId);
        return;
      }
      String prompt = prompts.getRawPrompt(PROMPT_KEY);
      ValidacionDocumentoJson raw = analisis.validarDocumento(prompt, texto);
      if (raw.esError()) {
        LOG.warn("EJ-3 minuta tercero IA error case={}: {}", caseId, raw.error());
        return;
      }
      List<ObservacionesCotejo.Entrada> nuevas = parsearObservaciones(raw.json(), documentoId);
      tx.executeWithoutResult(status -> persistir(tenantId, caseId, documentoId, nuevas));
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOG.warn("EJ-3 minuta tercero falló case={} doc={}: {}", caseId, documentoId, msg);
    }
  }

  private void persistir(
      UUID tenantId,
      UUID caseId,
      UUID documentoId,
      List<ObservacionesCotejo.Entrada> nuevas) {
    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseGet(() -> writingFiles.save(WritingFile.create(tenantId, caseId)));
    TitleStudy study =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(file.getId(), tenantId)
            .orElseGet(() -> titleStudies.save(TitleStudy.create(tenantId, file.getId())));

    // reemplazar borra todas las OPEN: conservar las de otros documentos / generales.
    List<ObservacionesCotejo.Entrada> merged = new ArrayList<>();
    for (ObservacionCotejoItem item :
        ObservacionesCotejo.pendientes(titleObservations, tenantId, study.getId())) {
      if (documentoId.equals(item.documentoId())) {
        continue;
      }
      merged.add(
          new ObservacionesCotejo.Entrada(
              item.code(),
              item.severidad(),
              item.mensaje(),
              item.documentoId() == null ? null : item.documentoId().toString(),
              item.documentosRelacionados().stream().map(r -> r.documentId().toString()).toList(),
              item.tipoDocumentoFaltante()));
    }
    merged.addAll(nuevas);

    Set<UUID> docs = new LinkedHashSet<>();
    for (LegalDocument d :
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, tenantId)) {
      docs.add(d.getId());
    }
    ObservacionesCotejo.reemplazar(titleObservations, tenantId, study.getId(), merged, docs);
    LOG.info(
        "EJ-3 minuta tercero persistió {} obs case={} doc={}", nuevas.size(), caseId, documentoId);
  }

  private List<ObservacionesCotejo.Entrada> parsearObservaciones(String json, UUID documentoId) {
    List<ObservacionesCotejo.Entrada> out = new ArrayList<>();
    if (!StringUtils.hasText(json)) {
      return out;
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode arr = root.path("observaciones");
      if (arr.isArray()) {
        for (JsonNode item : arr) {
          if (item == null || !item.isObject()) {
            continue;
          }
          String mensaje = texto(item, "mensaje", "message");
          if (!StringUtils.hasText(mensaje)) {
            continue;
          }
          String codigo = texto(item, "codigo", "code");
          String severidad = texto(item, "severidad", "severity");
          out.add(
              new ObservacionesCotejo.Entrada(
                  StringUtils.hasText(codigo) ? codigo : "MINUTA_TERCERO",
                  severidad,
                  mensaje.trim(),
                  documentoId.toString()));
        }
      }
      if (out.isEmpty()) {
        String estado = texto(root, "estado", "status");
        String razon = texto(root, "razon_rechazo", "razonRechazo", "motivo");
        if (StringUtils.hasText(razon)
            && estado != null
            && !"APROBADO".equalsIgnoreCase(estado.trim())
            && !"APPROVED".equalsIgnoreCase(estado.trim())) {
          out.add(
              new ObservacionesCotejo.Entrada(
                  "MINUTA_TERCERO", "HIGH", razon.trim(), documentoId.toString()));
        }
      }
    } catch (Exception e) {
      LOG.warn("EJ-3 JSON de observaciones ilegible: {}", e.getClass().getSimpleName());
    }
    return out;
  }

  private static String texto(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode v = node.get(key);
      if (v != null && v.isTextual() && StringUtils.hasText(v.asText())) {
        return v.asText();
      }
    }
    return null;
  }
}
