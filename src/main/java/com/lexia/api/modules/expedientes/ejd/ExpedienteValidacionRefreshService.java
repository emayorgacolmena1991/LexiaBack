package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Tras cambiar tipos documentales, re-ejecuta V1–V6, gates (p. ej. G-001) y sincroniza excepciones. */
@Service
public class ExpedienteValidacionRefreshService {

  private final LegalCaseRepository legalCases;
  private final ObjectProvider<EjdValidationEvaluationService> validationEvaluation;
  private final ObjectProvider<EjdGateEvaluationService> gateEvaluation;
  private final ObjectProvider<EjdCaseExceptionSyncService> exceptionSync;

  public ExpedienteValidacionRefreshService(
      LegalCaseRepository legalCases,
      ObjectProvider<EjdValidationEvaluationService> validationEvaluation,
      ObjectProvider<EjdGateEvaluationService> gateEvaluation,
      ObjectProvider<EjdCaseExceptionSyncService> exceptionSync) {
    this.legalCases = legalCases;
    this.validationEvaluation = validationEvaluation;
    this.gateEvaluation = gateEvaluation;
    this.exceptionSync = exceptionSync;
  }

  @Transactional
  public boolean refreshForExpediente(String caseId, UUID tenantId) {
    if (tenantId == null || !StringUtils.hasText(caseId)) {
      return false;
    }
    UUID id;
    try {
      id = UUID.fromString(caseId.trim());
    } catch (IllegalArgumentException ex) {
      return false;
    }
    LegalCase legalCase =
        legalCases.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId).orElse(null);
    if (legalCase == null) {
      return false;
    }
    validationEvaluation.ifAvailable(service -> service.refreshForCase(legalCase));
    gateEvaluation.ifAvailable(service -> service.refreshForCase(legalCase));
    exceptionSync.ifAvailable(service -> service.syncForCase(legalCase));
    return true;
  }
}