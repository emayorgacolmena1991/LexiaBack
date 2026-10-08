package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoint frontend: evalúa calidad/legibilidad de PDF o imagen (Azure prebuilt-layout).
 *
 * <pre>POST /api/v1/ia/calidad-documento  multipart field: file</pre>
 */
@RestController
@RequestMapping("/api/v1/ia")
public class CalidadDocumentoController {

  private final AzureCalidadDocumentoService calidadService;

  public CalidadDocumentoController(AzureCalidadDocumentoService calidadService) {
    this.calidadService = calidadService;
  }

  @PostMapping(value = "/calidad-documento", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CalidadDocumentoResultado evaluar(@RequestParam("file") MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Archivo requerido (campo multipart 'file').");
    }
    if (!calidadService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    try {
      String mime =
          StringUtils.hasText(file.getContentType())
              ? file.getContentType()
              : "application/octet-stream";
      return calidadService.evaluar(file.getBytes(), mime);
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(e.getMessage());
    } catch (IllegalStateException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "AZURE_ERROR", e.getMessage());
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "CALIDAD_ERROR", "Error evaluando calidad del archivo.");
    }
  }
}
