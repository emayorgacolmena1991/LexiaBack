package com.lexia.api.modules.expedientes.documentos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.PrevalidacionDocumentoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceService;

/** Orquestador de ingesta: Azure OCR por archivo. Sin llamada LLM. */
@Service
public class ProcesamientoDocumentalService {

  private static final Logger LOG = LoggerFactory.getLogger(ProcesamientoDocumentalService.class);
  private static final String ERROR_LECTURA = "No se pudo leer el documento.";
  private static final String ERROR_OCR_VACIO = "El documento no contiene texto legible.";

  private final AzureOcrService azureOcrService;
  private final CargaDocumentoService cargaDocumentoService;
  private final TenantGovernanceService governance;
  private final ObjectMapper objectMapper;
  private final Executor ocrExecutor;

  public ProcesamientoDocumentalService(
      AzureOcrService azureOcrService,
      CargaDocumentoService cargaDocumentoService,
      @Autowired(required = false) TenantGovernanceService governance,
      ObjectMapper objectMapper,
      @Qualifier("ocrExecutor") Executor ocrExecutor) {
    this.azureOcrService = azureOcrService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.governance = governance;
    this.objectMapper = objectMapper;
    this.ocrExecutor = ocrExecutor;
  }

  @Async
  public void procesarExpedienteCompletoAsync(
      String idExpediente, UUID tenantId, AuthPrincipal principal) {
    AuthPrincipal prev = AuthContext.get();
    try {
      if (principal != null) {
        AuthContext.set(principal);
      }
      procesarExpedienteCompleto(idExpediente, tenantId);
    } finally {
      AuthContext.clear();
      if (prev != null) {
        AuthContext.set(prev);
      }
    }
  }

  public void procesarExpedienteCompleto(String idExpediente, UUID tenantId) {
    List<StoredDoc> documentos;
    try {
      documentos = cargaDocumentoService.documentosParaProcesar(idExpediente);
    } catch (Exception e) {
      LOG.warn("Expediente {} no disponible para IA: {}", idExpediente, e.getMessage());
      cargaDocumentoService.marcarPrevalidacionError(idExpediente, e.getMessage());
      return;
    }

    boolean demo = false;
    if (tenantId != null && governance != null && !documentos.isEmpty()) {
      try {
        governance.assertDocumentExtractionAllowed(tenantId, documentos.size());
        String mode = governance.documentRoutingMode(tenantId);
        demo = mode != null && "DEMO".equals(mode.toUpperCase(Locale.ROOT));
      } catch (Exception e) {
        LOG.warn("Extracción no permitida para {}: {}", idExpediente, e.getMessage());
        cargaDocumentoService.marcarPrevalidacionError(idExpediente, e.getMessage());
        return;
      }
    }

    List<PrevalidacionDocumentoDTO> resultados = new ArrayList<>();
    int sumaCampos = 0;
    int legibles = 0;
    boolean huboErrorGrave = false;

    int totalDocs = documentos.size();
    List<CompletableFuture<DocOutcome>> futures = new ArrayList<>(totalDocs);
    for (int i = 0; i < totalDocs; i++) {
      StoredDoc doc = documentos.get(i);
      int idx = i + 1;
      boolean demoMode = demo;
      futures.add(
          CompletableFuture.supplyAsync(
              () -> procesarUnDocumento(idExpediente, doc, idx, totalDocs, demoMode),
              ocrExecutor));
    }

    for (CompletableFuture<DocOutcome> future : futures) {
      DocOutcome outcome = future.join();
      resultados.add(outcome.prevalidacion());
      sumaCampos += outcome.campos();
      if ("LEGIBLE".equals(outcome.prevalidacion().estado())) {
        legibles++;
      }
      if (outcome.errorGrave()) {
        huboErrorGrave = true;
      }
    }

    int total = resultados.size();
    int confianza = total == 0 ? 0 : Math.round((float) sumaCampos / total);
    String estadoGlobal =
        total == 0 ? "ERROR" : (huboErrorGrave && legibles == 0 ? "ERROR" : "COMPLETADA");
    cargaDocumentoService.completarPrevalidacion(
        idExpediente, estadoGlobal, confianza, legibles, total, resultados);
  }

  private DocOutcome procesarUnDocumento(
      String idExpediente, StoredDoc doc, int idx, int total, boolean demo) {
    String tipo = doc.codigoTipoDocumento();
    String nombre = doc.nombreOriginal();
    String textoOcr = null;
    try {
      LOG.info(
          "IA doc {}/{} id={} nombre={}", idx, total, doc.idDocumento(), nombre);

      Evaluacion evaluacion = evaluarDocumento(doc, demo);
      textoOcr = evaluacion.textoOcr();
      ExtraccionDocumento extraccion = evaluacion.extraccion();
      String analisisJson = objectMapper.writeValueAsString(extraccion.datos());
      guardarMemoria(
          idExpediente,
          doc,
          evaluacion.textoOcr(),
          analisisJson,
          extraccion.estado(),
          extraccion.motivo(),
          extraccion.camposDetectados());

      PrevalidacionDocumentoDTO row =
          new PrevalidacionDocumentoDTO(
              doc.idDocumento(), nombre, tipo, extraccion.estado(), extraccion.motivo());
      cargaDocumentoService.actualizarDocPrevalidacion(
          idExpediente,
          doc.idDocumento(),
          extraccion.estado(),
          extraccion.motivo(),
          extraccion.camposDetectados());
      return new DocOutcome(row, extraccion.camposDetectados(), "ERROR".equals(extraccion.estado()));
    } catch (Exception e) {
      LOG.warn(
          "Error IA doc={} exp={} ({}/{}): {}",
          doc.idDocumento(),
          idExpediente,
          idx,
          total,
          e.getMessage());
      String motivo = "Fallo OCR/análisis: " + safeMsg(e);
      guardarMemoria(idExpediente, doc, textoOcr, null, "ERROR", motivo, 0);
      PrevalidacionDocumentoDTO row =
          new PrevalidacionDocumentoDTO(doc.idDocumento(), nombre, tipo, "ERROR", motivo);
      cargaDocumentoService.actualizarDocPrevalidacion(
          idExpediente, doc.idDocumento(), "ERROR", motivo, 0);
      return new DocOutcome(row, 0, true);
    }
  }

  private Evaluacion evaluarDocumento(StoredDoc doc, boolean demo) throws Exception {
    if (demo) {
      return new Evaluacion(
          "", new ExtraccionDocumento(DatosExtraidosDTO.empty(), "LEGIBLE", null, 0));
    }
    if (doc.bytes() == null || doc.bytes().length == 0) {
      return new Evaluacion("", ExtraccionDocumento.error(ERROR_LECTURA));
    }

    String textoOcr;
    boolean ocrEjecutado = false;
    if (azureOcrService.isConfigured()) {
      textoOcr = azureOcrService.extraerTexto(doc.bytes(), doc.mimeType());
      ocrEjecutado = true;
    } else {
      LOG.warn(
          "Azure OCR no configurado; texto vacío para {}. Define AZURE_DOCUMENT_INTELLIGENCE_* en .env",
          doc.idDocumento());
      textoOcr = "";
    }
    if (textoOcr == null) {
      textoOcr = "";
    }
    if (!StringUtils.hasText(textoOcr)) {
      return new Evaluacion(
          textoOcr,
          ExtraccionDocumento.error(ocrEjecutado ? ERROR_OCR_VACIO : "Azure OCR no configurado."));
    }

    return new Evaluacion(
        textoOcr, new ExtraccionDocumento(DatosExtraidosDTO.empty(), "LEGIBLE", null, 100));
  }

  private void guardarMemoria(
      String idExpediente,
      StoredDoc doc,
      String textoOcr,
      String analisisJson,
      String estado,
      String motivo,
      Integer confianza) {
    cargaDocumentoService.guardarOcrEnMemoria(
        idExpediente,
        new DocumentoOcrResultadoDTO(
            doc.idDocumento(),
            doc.nombreOriginal(),
            doc.codigoTipoDocumento(),
            textoOcr,
            analisisJson,
            estado,
            motivo,
            confianza));
  }

  private static String safeMsg(Exception e) {
    String m = e.getMessage();
    if (!StringUtils.hasText(m)) {
      return e.getClass().getSimpleName();
    }
    return m.length() > 240 ? m.substring(0, 240) + "…" : m;
  }

  private record Evaluacion(String textoOcr, ExtraccionDocumento extraccion) {}

  private record DocOutcome(
      PrevalidacionDocumentoDTO prevalidacion, int campos, boolean errorGrave) {}
}
