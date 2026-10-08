package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ArchivoEstadoDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ExpedienteExtraidoDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.lexia.api.modules.expedientes.ocr.AzureDocumentIntelligenceException;
import com.lexia.api.modules.expedientes.ocr.AzureDocumentIntelligenceService;
import com.lexia.api.modules.expedientes.ocr.OcrContentReducerService;
import com.lexia.api.modules.expedientes.ocr.OcrTextResult;
import com.lexia.api.modules.expedientes.reglas.PrevalidacionDtos;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceService;

@Service
public class DocumentExtractorService {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentExtractorService.class);

  private final AzureDocumentIntelligenceService azureDocumentIntelligenceService;
  private final OcrContentReducerService ocrContentReducerService;
  private final TenantGovernanceService governance;

  public DocumentExtractorService(
      AzureDocumentIntelligenceService azureDocumentIntelligenceService,
      OcrContentReducerService ocrContentReducerService,
      @Autowired(required = false) TenantGovernanceService governance) {
    this.azureDocumentIntelligenceService = azureDocumentIntelligenceService;
    this.ocrContentReducerService = ocrContentReducerService;
    this.governance = governance;
  }

  public ExpedienteExtraidoDTO extraerInformacion(List<MultipartFile> archivos) {
    List<ArchivoEstadoDTO> estados = new ArrayList<>();
    AuthPrincipal auth = AuthContext.get();
    UUID tenantId = auth != null ? auth.tenantId() : null;
    if (tenantId != null && governance != null) {
      governance.assertDocumentExtractionAllowed(tenantId, archivos.size());
    }
    boolean demoRouting =
        tenantId != null
            && governance != null
            && "DEMO".equals(governance.documentRoutingMode(tenantId).toUpperCase(Locale.ROOT));

    for (MultipartFile archivo : archivos) {
      String nombre =
          archivo.getOriginalFilename() != null ? archivo.getOriginalFilename() : "sin-nombre";
      try {
        byte[] bytes = archivo.getBytes();
        String contentType = archivo.getContentType();
        if (!StringUtils.hasText(contentType)) {
          contentType = "application/pdf";
        }

        if (demoRouting) {
          estados.add(new ArchivoEstadoDTO(nombre, "PROCESADO"));
          continue;
        }
        if (!azureDocumentIntelligenceService.isConfigured()) {
          LOG.warn("Azure OCR no configurado para {}", nombre);
          estados.add(new ArchivoEstadoDTO(nombre, "ERROR"));
          continue;
        }
        OcrTextResult ocrResult = azureDocumentIntelligenceService.analyze(bytes, contentType);
        String texto =
            ocrResult == null ? "" : ocrContentReducerService.reduce(ocrResult.text());
        if (!StringUtils.hasText(texto)) {
          estados.add(new ArchivoEstadoDTO(nombre, "ERROR"));
          continue;
        }
        estados.add(new ArchivoEstadoDTO(nombre, "PROCESADO"));
      } catch (Exception e) {
        LOG.warn("Error procesando archivo {}: {}", nombre, e.getMessage());
        estados.add(new ArchivoEstadoDTO(nombre, "ERROR"));
      }
    }

    return new ExpedienteExtraidoDTO(estados, DatosExtraidosDTO.empty());
  }

  public void reservarExtraccion(int fileCount) {
    AuthPrincipal auth = AuthContext.get();
    UUID tenantId = auth != null ? auth.tenantId() : null;
    if (tenantId != null && governance != null) {
      governance.assertDocumentExtractionAllowed(tenantId, fileCount);
    }
  }

  public boolean esEnrutamientoDemo() {
    AuthPrincipal auth = AuthContext.get();
    UUID tenantId = auth != null ? auth.tenantId() : null;
    if (tenantId == null || governance == null) {
      return false;
    }
    String mode = governance.documentRoutingMode(tenantId);
    return mode != null && "DEMO".equals(mode.toUpperCase(Locale.ROOT));
  }

  public PrevalidacionLectura prevalidar(
      byte[] bytes, String mimeType, String tipoEsperado, boolean demo) {
    if (demo) {
      return PrevalidacionLectura.legible();
    }
    if (bytes == null || bytes.length == 0) {
      return PrevalidacionLectura.error("No se pudo leer el documento.");
    }
    String contentType = StringUtils.hasText(mimeType) ? mimeType : "application/pdf";
    if (!azureDocumentIntelligenceService.isConfigured()) {
      return PrevalidacionLectura.error("Azure OCR no configurado.");
    }
    try {
      OcrTextResult ocrResult = azureDocumentIntelligenceService.analyze(bytes, contentType);
      String texto =
          ocrResult == null ? "" : ocrContentReducerService.reduce(ocrResult.text());
      if (!StringUtils.hasText(texto)) {
        return PrevalidacionLectura.error("El documento no contiene texto legible.");
      }
      return PrevalidacionLectura.legible();
    } catch (AuthException ex) {
      throw ex;
    } catch (AzureDocumentIntelligenceException ex) {
      LOG.warn("Prevalidación: lectura OCR fallida", ex);
      return PrevalidacionLectura.error("No se pudo leer el documento.");
    } catch (RuntimeException ex) {
      LOG.warn("Prevalidación: interpretación fallida", ex);
      return PrevalidacionLectura.revisar("No se pudo interpretar el contenido.");
    }
  }

  public record PrevalidacionLectura(String estado, String motivo) {

    static PrevalidacionLectura legible() {
      return new PrevalidacionLectura(PrevalidacionDtos.LEGIBLE, null);
    }

    static PrevalidacionLectura revisar(String motivo) {
      return new PrevalidacionLectura(PrevalidacionDtos.REVISAR, motivo);
    }

    static PrevalidacionLectura error(String motivo) {
      return new PrevalidacionLectura(PrevalidacionDtos.ERROR, motivo);
    }
  }
}
