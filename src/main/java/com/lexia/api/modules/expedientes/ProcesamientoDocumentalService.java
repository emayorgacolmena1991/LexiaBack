package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.PrevalidacionDocumentoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.llm.AnalisisDocumentoService;
import com.lexia.api.modules.expedientes.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.expedientes.ocr.AzureOcrService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Orquestador: Azure OCR → memoria borrador → LLM (gemini|claude) → prevalidación. */
@Service
public class ProcesamientoDocumentalService {

  private static final Logger LOG = LoggerFactory.getLogger(ProcesamientoDocumentalService.class);

  private final AzureOcrService azureOcrService;
  private final AnalisisDocumentoService analisisDocumentoService;
  private final CargaDocumentoService cargaDocumentoService;
  private final ObjectMapper objectMapper;

  public ProcesamientoDocumentalService(
      AzureOcrService azureOcrService,
      AnalisisDocumentoService analisisDocumentoService,
      CargaDocumentoService cargaDocumentoService,
      ObjectMapper objectMapper) {
    this.azureOcrService = azureOcrService;
    this.analisisDocumentoService = analisisDocumentoService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.objectMapper = objectMapper;
  }

  @Async
  public void procesarExpedienteCompletoAsync(String idExpediente, UUID tenantId) {
    AuthPrincipal prev = AuthContext.get();
    try {
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

    List<PrevalidacionDocumentoDTO> resultados = new ArrayList<>();
    int sumaCampos = 0;
    int legibles = 0;
    boolean huboErrorGrave = false;

    for (StoredDoc doc : documentos) {
      String tipo = doc.codigoTipoDocumento();
      String nombre = doc.nombreOriginal();
      try {
        String textoOcr;
        if (azureOcrService.isConfigured()) {
          textoOcr = azureOcrService.extraerTexto(doc.bytes(), doc.mimeType());
        } else {
          LOG.warn(
              "Azure OCR no configurado; texto vacío para {}. Define AZURE_DOCUMENT_INTELLIGENCE_* en .env",
              doc.idDocumento());
          textoOcr = "";
        }

        guardarMemoria(
            idExpediente, doc, textoOcr, null, "EN_PROCESO", null, null);

        ExtraccionDocumento extraccion =
            analisisDocumentoService.extraerDatosClave(textoOcr, tipo);
        String analisisJson = objectMapper.writeValueAsString(extraccion.datos());
        guardarMemoria(
            idExpediente,
            doc,
            textoOcr,
            analisisJson,
            extraccion.estado(),
            extraccion.motivo(),
            extraccion.camposDetectados());

        resultados.add(
            new PrevalidacionDocumentoDTO(
                doc.idDocumento(),
                nombre,
                tipo,
                extraccion.estado(),
                extraccion.motivo()));
        sumaCampos += extraccion.camposDetectados();
        if ("LEGIBLE".equals(extraccion.estado())) {
          legibles++;
        }
        if ("ERROR".equals(extraccion.estado())) {
          huboErrorGrave = true;
        }

        cargaDocumentoService.actualizarDocPrevalidacion(
            idExpediente,
            doc.idDocumento(),
            extraccion.estado(),
            extraccion.motivo(),
            extraccion.camposDetectados());
      } catch (Exception e) {
        LOG.warn(
            "Error IA doc={} exp={}: {}",
            doc.idDocumento(),
            idExpediente,
            e.getMessage());
        huboErrorGrave = true;
        String motivo = "Fallo OCR/análisis: " + safeMsg(e);
        guardarMemoria(idExpediente, doc, null, null, "ERROR", motivo, 0);
        resultados.add(
            new PrevalidacionDocumentoDTO(doc.idDocumento(), nombre, tipo, "ERROR", motivo));
        cargaDocumentoService.actualizarDocPrevalidacion(
            idExpediente, doc.idDocumento(), "ERROR", motivo, 0);
      }
    }

    int total = resultados.size();
    int confianza = total == 0 ? 0 : Math.round((float) sumaCampos / total);
    String estadoGlobal =
        total == 0 ? "ERROR" : (huboErrorGrave && legibles == 0 ? "ERROR" : "COMPLETADA");
    cargaDocumentoService.completarPrevalidacion(
        idExpediente, estadoGlobal, confianza, legibles, total, resultados);
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
}
