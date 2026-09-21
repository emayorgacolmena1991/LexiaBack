package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.ExpedienteDtos.ArchivoEstadoDTO;
import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.expedientes.ExpedienteDtos.ExpedienteExtraidoDTO;
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

@Service
public class DocumentExtractorService {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentExtractorService.class);

  private final OcrService ocrService;
  private final TenantGovernanceService governance;

  public DocumentExtractorService(
      OcrService ocrService,
      @Autowired(required = false) TenantGovernanceService governance) {
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
      String nombre = archivo.getOriginalFilename() != null ? archivo.getOriginalFilename() : "sin-nombre";
      try {
        byte[] bytes = archivo.getBytes();
        String contentType = archivo.getContentType();
        if (!StringUtils.hasText(contentType)) {
          contentType = "application/pdf";
        }

        DatosExtraidosDTO parcial =
            demoRouting ? DatosExtraidosDTO.empty() : ocrService.analizarDocumento(bytes, contentType);
        datosConsolidados = fusionarDatos(datosConsolidados, parcial);
        estados.add(new ArchivoEstadoDTO(nombre, "PROCESADO"));
      } catch (Exception e) {
        LOG.warn("Error procesando archivo {}: {}", nombre, e.getMessage());
        estados.add(new ArchivoEstadoDTO(nombre, "ERROR"));
      }
    }

    return new ExpedienteExtraidoDTO(estados, datosConsolidados);
  }

  private DatosExtraidosDTO fusionarDatos(DatosExtraidosDTO actual, DatosExtraidosDTO nuevo) {
    return new DatosExtraidosDTO(
        preferir(nuevo.tipoDocumento(), actual.tipoDocumento()),
        preferir(nuevo.numeroEscritura(), actual.numeroEscritura()),
        preferir(nuevo.fechaEscritura(), actual.fechaEscritura()),
        preferir(nuevo.notaria(), actual.notaria()),
        preferir(nuevo.municipio(), actual.municipio()),
        preferir(nuevo.comparecientes(), actual.comparecientes()),
        preferir(nuevo.nitIdentificacion(), actual.nitIdentificacion()),
        preferir(nuevo.objetoAsunto(), actual.objetoAsunto()));
  }

  private static String preferir(String nuevo, String actual) {
    return StringUtils.hasText(nuevo) ? nuevo : actual;
  }
}
