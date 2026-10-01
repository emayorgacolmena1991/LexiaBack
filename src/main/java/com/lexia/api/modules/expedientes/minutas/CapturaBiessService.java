package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCapturaBiess;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lee una captura de la plataforma BIESS (Azure OCR → LLM en modo JSON/tool) y devuelve monto,
 * tasa, plazo, cuota y apoderado. La captura no es un documento del expediente: no entra al cotejo
 * ni a la caché OCR. Los cinco campos sí se guardan en {@code extracted_data} (grupo {@code biess}).
 */
@Service
public class CapturaBiessService {

  private static final Logger LOG = LoggerFactory.getLogger(CapturaBiessService.class);
  private static final long MAX_BYTES = 10L * 1024 * 1024;
  private static final Map<String, String> MIME_BY_EXTENSION =
      Map.of(
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "pdf", "application/pdf");

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final AzureOcrService ocr;
  private final AnalisisDocumentoService analisis;
  private final DatosBiessStore datosBiess;

  public CapturaBiessService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      AzureOcrService ocr,
      AnalisisDocumentoService analisis,
      DatosBiessStore datosBiess) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.ocr = ocr;
    this.analisis = analisis;
    this.datosBiess = datosBiess;
  }

  public DatosBiessMinuta extraer(UUID caseId, MultipartFile file) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Adjunta la captura BIESS (JPG, PNG o PDF).");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ApiException.badRequest("La captura BIESS supera el máximo de 10 MB.");
    }
    String mime = resolveMime(file);
    if (!ocr.isConfigured()) {
      throw ApiException.badRequest("Azure Document Intelligence no está configurado.");
    }
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para leer la captura BIESS.");
    }

    String texto;
    try {
      texto = ocr.extraerTexto(file.getBytes(), mime);
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo de la captura BIESS.");
    } catch (RuntimeException e) {
      LOG.warn("OCR captura BIESS falló case={}: {}", caseId, e.getMessage());
      throw ApiException.badRequest("No se pudo leer el texto de la captura BIESS.");
    }

    ExtraccionCapturaBiess extraccion = analisis.extraerCapturaBiess(texto);
    if (extraccion == null || "ERROR".equals(extraccion.estado())) {
      throw ApiException.badRequest(
          extraccion != null && StringUtils.hasText(extraccion.motivo())
              ? extraccion.motivo()
              : "No se pudieron extraer datos de la captura BIESS.");
    }
    DatosBiessMinuta data = extraccion.data() == null ? DatosBiessMinuta.empty() : extraccion.data();
    datosBiess.guardar(tenantId, caseId, data);
    LOG.info("Captura BIESS persistida case={} bytes={}", caseId, file.getSize());
    return data;
  }

  private static String resolveMime(MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String mime = MIME_BY_EXTENSION.get(ext);
    if (mime == null) {
      throw ApiException.badRequest("Formato no permitido. Solo JPG, PNG o PDF.");
    }
    return mime;
  }
}
