package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ArchivoEstadoDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ExpedienteExtraidoDTO;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.lexia.api.modules.expedientes.ocr.OcrService;
import com.lexia.api.modules.expedientes.ocr.OcrTextResult;
import com.lexia.api.modules.expedientes.reglas.PrevalidacionDtos;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceService;

@Service
public class DocumentExtractorService {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentExtractorService.class);

  private final AzureDocumentIntelligenceService azureDocumentIntelligenceService;
  private final OcrContentReducerService ocrContentReducerService;
  private final OcrService ocrService;
  private final TenantGovernanceService governance;

  public DocumentExtractorService(
      AzureDocumentIntelligenceService azureDocumentIntelligenceService,
      OcrContentReducerService ocrContentReducerService,
      OcrService ocrService,
      @Autowired(required = false) TenantGovernanceService governance) {
    this.azureDocumentIntelligenceService = azureDocumentIntelligenceService;
    this.ocrContentReducerService = ocrContentReducerService;
    this.ocrService = ocrService;
    this.governance = governance;
  }

  public ExpedienteExtraidoDTO extraerInformacion(List<MultipartFile> archivos) {
    List<ArchivoEstadoDTO> estados = new ArrayList<>();
    DatosExtraidosDTO datosConsolidados = DatosExtraidosDTO.empty();
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

        DatosExtraidosDTO parcial = extraerDatos(bytes, contentType, demoRouting);
        datosConsolidados = fusionarDatos(datosConsolidados, parcial);
        estados.add(new ArchivoEstadoDTO(nombre, "PROCESADO"));
      } catch (Exception e) {
        LOG.warn("Error procesando archivo {}: {}", nombre, e.getMessage());
        estados.add(new ArchivoEstadoDTO(nombre, "ERROR"));
      }
    }

    return new ExpedienteExtraidoDTO(estados, datosConsolidados);
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
    try {
      if (!azureDocumentIntelligenceService.isConfigured()) {
        return clasificarPrevalidacion(ocrService.analizarDocumento(bytes, contentType), tipoEsperado);
      }
      OcrTextResult ocrResult = azureDocumentIntelligenceService.analyze(bytes, contentType);
      String texto =
          ocrResult == null ? "" : ocrContentReducerService.reduce(ocrResult.text());
      if (!StringUtils.hasText(texto)) {
        return PrevalidacionLectura.error("El documento no contiene texto legible.");
      }
      return clasificarPrevalidacion(ocrService.analizarTexto(texto), tipoEsperado);
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

  private PrevalidacionLectura clasificarPrevalidacion(
      DatosExtraidosDTO datos, String tipoEsperado) {
    if (datos == null || sinCampos(datos)) {
      return PrevalidacionLectura.revisar("Extracción incompleta.");
    }
    if (StringUtils.hasText(datos.tipoDocumento())
        && StringUtils.hasText(tipoEsperado)
        && !tiposCoinciden(datos.tipoDocumento(), tipoEsperado)) {
      return PrevalidacionLectura.revisar("El tipo detectado no coincide con la clasificación.");
    }
    return PrevalidacionLectura.legible();
  }

  private static boolean sinCampos(DatosExtraidosDTO datos) {
    return !StringUtils.hasText(datos.tipoDocumento())
        && !StringUtils.hasText(datos.resumen())
        && datos.datosClave().isEmpty();
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
    String sinAcentos =
        Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return sinAcentos.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
  }

  private DatosExtraidosDTO extraerDatos(byte[] bytes, String contentType, boolean demoRouting) {
    if (demoRouting) {
      return DatosExtraidosDTO.empty();
    }
    if (!azureDocumentIntelligenceService.isConfigured()) {
      return ocrService.analizarDocumento(bytes, contentType);
    }
    OcrTextResult ocrResult = azureDocumentIntelligenceService.analyze(bytes, contentType);
    String textoReducido = ocrContentReducerService.reduce(ocrResult.text());
    return ocrService.analizarTexto(textoReducido);
  }

  private DatosExtraidosDTO fusionarDatos(DatosExtraidosDTO actual, DatosExtraidosDTO nuevo) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (actual != null && actual.datosClave() != null) {
      merged.putAll(actual.datosClave());
    }
    if (nuevo != null && nuevo.datosClave() != null) {
      merged.putAll(nuevo.datosClave());
    }
    return new DatosExtraidosDTO(
        preferir(
            nuevo == null ? null : nuevo.tipoDocumento(),
            actual == null ? null : actual.tipoDocumento()),
        preferir(
            nuevo == null ? null : nuevo.resumen(), actual == null ? null : actual.resumen()),
        Map.copyOf(merged));
  }

  private static String preferir(String nuevo, String actual) {
    return StringUtils.hasText(nuevo) ? nuevo : actual;
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
