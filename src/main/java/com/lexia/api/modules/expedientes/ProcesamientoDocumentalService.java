package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.PrevalidacionDocumentoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Orquestador: Azure OCR → memoria borrador → LLM (gemini|claude) → prevalidación. */
@Service
public class ProcesamientoDocumentalService {

  private static final Logger LOG = LoggerFactory.getLogger(ProcesamientoDocumentalService.class);
  private static final String ERROR_LECTURA = "No se pudo leer el documento.";
  private static final String ERROR_OCR_VACIO = "El documento no contiene texto legible.";
  private static final String REVISAR_TIPO = "El tipo detectado no coincide con la clasificación.";

  private final AzureOcrService azureOcrService;
  private final AnalisisDocumentoService analisisDocumentoService;
  private final CargaDocumentoService cargaDocumentoService;
  private final TenantGovernanceService governance;
  private final ObjectMapper objectMapper;

  public ProcesamientoDocumentalService(
      AzureOcrService azureOcrService,
      AnalisisDocumentoService analisisDocumentoService,
      CargaDocumentoService cargaDocumentoService,
      @Autowired(required = false) TenantGovernanceService governance,
      ObjectMapper objectMapper) {
    this.azureOcrService = azureOcrService;
    this.analisisDocumentoService = analisisDocumentoService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.governance = governance;
    this.objectMapper = objectMapper;
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

    for (StoredDoc doc : documentos) {
      String tipo = doc.codigoTipoDocumento();
      String nombre = doc.nombreOriginal();
      try {
        Evaluacion evaluacion = evaluarDocumento(idExpediente, doc, tipo, demo);
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

  private Evaluacion evaluarDocumento(
      String idExpediente, StoredDoc doc, String tipo, boolean demo) throws Exception {
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
    if (ocrEjecutado && !StringUtils.hasText(textoOcr)) {
      return new Evaluacion(textoOcr, ExtraccionDocumento.error(ERROR_OCR_VACIO));
    }

    guardarMemoria(idExpediente, doc, textoOcr, null, "EN_PROCESO", null, null);

    ExtraccionDocumento extraccion = analisisDocumentoService.extraerDatosClave(textoOcr, tipo);
    return new Evaluacion(textoOcr, ajustarPorTipo(extraccion, tipo));
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

  private static ExtraccionDocumento ajustarPorTipo(
      ExtraccionDocumento extraccion, String tipoEsperado) {
    if (extraccion == null || !"LEGIBLE".equals(extraccion.estado())) {
      return extraccion;
    }
    String detectado = extraccion.datos() == null ? null : extraccion.datos().tipoDocumento();
    if (tiposCoinciden(detectado, tipoEsperado)) {
      return extraccion;
    }
    return new ExtraccionDocumento(
        extraccion.datos(), "REVISAR", REVISAR_TIPO, extraccion.camposDetectados());
  }

  private static boolean tiposCoinciden(String detectado, String esperado) {
    String encontrado = normalizarTipo(detectado);
    String clasificado = normalizarTipo(esperado);
    if (encontrado.isEmpty() || clasificado.isEmpty()) {
      return true;
    }
    if (encontrado.contains(clasificado) || clasificado.contains(encontrado)) {
      return true;
    }
    for (String token : clasificado.split(" ")) {
      if (token.length() >= 4 && encontrado.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizarTipo(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String sinAcentos = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return sinAcentos.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
  }

  private static String safeMsg(Exception e) {
    String m = e.getMessage();
    if (!StringUtils.hasText(m)) {
      return e.getClass().getSimpleName();
    }
    return m.length() > 240 ? m.substring(0, 240) + "…" : m;
  }

  private record Evaluacion(String textoOcr, ExtraccionDocumento extraccion) {}
}
