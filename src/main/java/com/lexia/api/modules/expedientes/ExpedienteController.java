package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.ExpedienteDtos.ExpedienteExtraidoDTO;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/expedientes")
public class ExpedienteController {

  private final DocumentExtractorService extractorService;

  public ExpedienteController(DocumentExtractorService extractorService) {
    this.extractorService = extractorService;
  }

  @PostMapping(value = "/procesar-documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ExpedienteExtraidoDTO> procesarDocumentos(
      @RequestParam("archivos") List<MultipartFile> archivos) {
    if (archivos == null || archivos.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(extractorService.extraerInformacion(archivos));
  }
}
