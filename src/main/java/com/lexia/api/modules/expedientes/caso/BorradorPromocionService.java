package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.PromoverBorradorRequest;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Convierte {@code expediente_borrador} en {@code legal_case} (EJD + writing file). Idempotente:
 * si ya se promovió, devuelve el mismo id.
 */
@Service
public class BorradorPromocionService {

  private final ExpedienteBorradorStore borradores;
  private final LegalCaseRepository legalCases;
  private final ObjectProvider<CaseService> caseServices;
  private final ObjectProvider<ExpedienteSeguimientoService> seguimiento;

  public BorradorPromocionService(
      ExpedienteBorradorStore borradores,
      LegalCaseRepository legalCases,
      ObjectProvider<CaseService> caseServices,
      ObjectProvider<ExpedienteSeguimientoService> seguimiento) {
    this.borradores = borradores;
    this.legalCases = legalCases;
    this.caseServices = caseServices;
    this.seguimiento = seguimiento;
  }

  @Transactional
  public CaseDetailItem promover(PromoverBorradorRequest request) {
    if (request == null || request.draftId() == null) {
      throw ApiException.badRequest("draftId es obligatorio.");
    }
    UUID caseId = asegurarExpediente(request.draftId(), request);
    CaseService cases = requireCases();
    return cases.getCase(caseId);
  }

  /** Id oficial. Si {@code id} ya es expediente, lo devuelve. Si es borrador, lo promueve. */
  @Transactional
  public UUID asegurarExpediente(UUID id) {
    return asegurarExpediente(id, null);
  }

  private UUID asegurarExpediente(UUID id, PromoverBorradorRequest extra) {
    UUID tenantId = AuthContext.require().tenantId();
    if (legalCases.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId).isPresent()) {
      return id;
    }
    ExpedienteBorrador draft =
        borradores
            .find(id, tenantId)
            .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
    if (draft.getPromotedCaseId() != null
        && legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(draft.getPromotedCaseId(), tenantId)
            .isPresent()) {
      return draft.getPromotedCaseId();
    }
    CaseDetailItem created = requireCases().createCase(toCreateRequest(draft, extra));
    borradores.marcarPromovido(draft.getId(), tenantId, created.id());
    seguimiento.ifAvailable(service -> service.vincularBorrador(draft.getId().toString(), created.id()));
    return created.id();
  }

  private CaseService requireCases() {
    CaseService cases = caseServices.getIfAvailable();
    if (cases == null) {
      throw ApiException.badRequest("No se puede promover el borrador: persistencia no disponible.");
    }
    return cases;
  }

  private static CreateCaseRequest toCreateRequest(
      ExpedienteBorrador draft, PromoverBorradorRequest extra) {
    String product = text(draft.getProductCode());
    String subject = first(extra == null ? null : extra.subject(), product, "Escrituración BIESS");
    String client = first(extra == null ? null : extra.clientName(), subject);
    String operation =
        first(
            extra == null ? null : extra.operationTypeCode(),
            "SUSTITUCION_HIPOTECA".equals(product) ? "HIPOTECA" : "COMPRAVENTA");
    return new CreateCaseRequest(
        subject,
        "Escrituración",
        "EJD",
        client,
        extra == null ? null : blankToNull(extra.identification()),
        extra == null ? null : blankToNull(extra.stage()),
        extra == null ? null : blankToNull(extra.priority()),
        extra == null ? null : blankToNull(extra.participants()),
        operation,
        product,
        draft.getIngestionMode(),
        text(draft.getCanton()));
  }

  private static String first(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private static String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
