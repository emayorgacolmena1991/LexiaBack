package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PrevalidacionWorker {

  private static final Logger LOG = LoggerFactory.getLogger(PrevalidacionWorker.class);

  private final DocumentExtractorService documentExtractorService;
  private final PrevalidacionService prevalidacionService;

  public PrevalidacionWorker(
      DocumentExtractorService documentExtractorService,
      @Lazy PrevalidacionService prevalidacionService) {
    this.documentExtractorService = documentExtractorService;
    this.prevalidacionService = prevalidacionService;
  }

  @Async
  public void procesar(
      String idExpediente,
      List<CargaDocumentoService.StoredDoc> documentos,
      AuthPrincipal principal) {
    if (principal != null) {
      AuthContext.set(principal);
    }
    try {
      documentExtractorService.reservarExtraccion(documentos.size());
      boolean demo = documentExtractorService.esEnrutamientoDemo();
      for (CargaDocumentoService.StoredDoc doc : documentos) {
        DocumentExtractorService.PrevalidacionLectura lectura =
            documentExtractorService.prevalidar(
                doc.bytes(), doc.mimeType(), doc.codigoTipoDocumento(), demo);
        prevalidacionService.marcarDocumento(
            idExpediente, doc.idDocumento(), lectura.estado(), lectura.motivo());
      }
      prevalidacionService.completar(idExpediente);
    } catch (Exception ex) {
      LOG.warn("Prevalidación no completada para {}", idExpediente, ex);
      prevalidacionService.fallar(idExpediente);
    } finally {
      AuthContext.clear();
    }
  }
}
