package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.ExpedienteDtos.ResolveGateRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseGateResolutionService {

  private final CaseGateRepository caseGates;
  private final CaseNoteRepository caseNotes;
  private final AuthorizationService authorization;

  public CaseGateResolutionService(
      CaseGateRepository caseGates,
      CaseNoteRepository caseNotes,
      AuthorizationService authorization) {
    this.caseGates = caseGates;
    this.caseNotes = caseNotes;
    this.authorization = authorization;
  }

  @Transactional
  public void resolve(UUID caseId, UUID caseGateId, ResolveGateRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();

    CaseGate gate =
        caseGates.findByIdAndTenantId(caseGateId, tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Gate no encontrado."));
    if (!gate.getCaseId().equals(caseId)) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "GATE_SCOPE", "El gate no pertenece al expediente.");
    }

    String normalized = request.result().trim().toUpperCase(Locale.ROOT);
    if (!"PASS".equals(normalized) && !"FAIL".equals(normalized)) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_RESULT", "Resultado debe ser PASS o FAIL.");
    }
    gate.applyResult(normalized);
    caseGates.save(gate);

    String note =
        "Gate resuelto manualmente: "
            + normalized
            + (request.comment() != null && !request.comment().isBlank()
                ? ". " + request.comment().trim()
                : "");
    caseNotes.save(CaseNote.create(tenantId, caseId, userId, note));
  }
}
