package com.lexia.api.modules.expedientes.requisitos;

import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ValidacionesExpedienteDTO;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.VigenciaDocumentoDTO;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.expedientes.requisitos.VigenciaDocumentoService.ResultadoVigencia;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcula y expone las validaciones del expediente: vigencia de documentos. No condicional: usa
 * ObjectProvider para VigenciaDocumentoService que sí lo es.
 */
@Service
public class ValidacionesExpedienteService {

  private final CaseDocumentoStore caseDocs;
  private final LegalDocumentRepository documentRepo;
  private final ProductoBiessService productos;
  private final ObjectProvider<VigenciaDocumentoService> vigenciaProvider;

  public ValidacionesExpedienteService(
      CaseDocumentoStore caseDocs,
      LegalDocumentRepository documentRepo,
      ProductoBiessService productos,
      ObjectProvider<VigenciaDocumentoService> vigenciaProvider) {
    this.caseDocs = caseDocs;
    this.documentRepo = documentRepo;
    this.productos = productos;
    this.vigenciaProvider = vigenciaProvider;
  }

  @Transactional(readOnly = true)
  public ValidacionesExpedienteDTO validaciones(String idExpediente) {
    VigenciaDocumentoService svc = vigenciaProvider.getIfAvailable();
    if (svc == null) {
      return new ValidacionesExpedienteDTO(List.of());
    }
    CaseDocumentoStore.CaseFileContext ctx = caseDocs.context(idExpediente);
    if (ctx == null) {
      return new ValidacionesExpedienteDTO(List.of());
    }
    List<LegalDocument> docs =
        documentRepo.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            ctx.caseId(), ctx.tenantId());
    List<RequisitosService.RequisitoAplicable> requisitos =
        productos
            .resolverRequisitos(ctx.tenantId(), ctx.caseId(), ctx.productCode(), ctx.canton())
            .requisitos();
    List<ResultadoVigencia> resultados = svc.calcular(ctx.tenantId(), ctx.caseId(), docs, requisitos);
    List<VigenciaDocumentoDTO> dtos =
        resultados.stream()
            .map(
                r ->
                    new VigenciaDocumentoDTO(
                        r.documentId() != null ? r.documentId().toString() : null,
                        r.docType(),
                        r.docNombre(),
                        r.estado().name(),
                        r.mensaje(),
                        r.fechaEfectiva()))
            .toList();
    return new ValidacionesExpedienteDTO(dtos);
  }
}
