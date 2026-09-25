package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResolveExceptionRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseExceptionResolutionService {

  private final CaseExceptionRepository exceptions;
  private final CaseNoteRepository caseNotes;
  private final CaseActionRepository caseActions;
  private final AuthorizationService authorization;

  public CaseExceptionResolutionService(
      CaseExceptionRepository exceptions,
      CaseNoteRepository caseNotes,
      CaseActionRepository caseActions,
      AuthorizationService authorization) {
    this.exceptions = exceptions;
    this.caseNotes = caseNotes;
    this.caseActions = caseActions;
    this.authorization = authorization;
  }

  @Transactional
  public void resolve(UUID caseId, UUID exceptionId, ResolveExceptionRequest request) {
    requireResolvePermission();
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();

    CaseException row =
        exceptions
            .findById(exceptionId)
            .filter(item -> item.getTenantId().equals(tenantId))
            .orElseThrow(
                () ->
                    new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Excepción no encontrada."));
    if (!row.getCaseId().equals(caseId)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "EXCEPTION_SCOPE", "La excepción no pertenece al expediente.");
    }
    if (!"OPEN".equals(row.getStatus())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "ALREADY_RESOLVED", "La excepción ya está resuelta.");
    }

    String resolution = request.resolution().trim();
    if (resolution.length() < 10) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "RESOLUTION_TOO_SHORT",
          "Describe la resolución con al menos 10 caracteres.");
    }

    row.resolve(resolution);
    exceptions.save(row);

    String note = "Excepción resuelta: " + row.getTitle() + ". " + resolution;
    caseNotes.save(CaseNote.create(tenantId, caseId, userId, note));
    caseActions.save(
        CaseAction.create(
            tenantId,
            caseId,
            "Excepción resuelta",
            "EXCEPTION_RESOLVED",
            "COMPLETED",
            userId,
            java.time.Instant.now()));
  }

  private void requireResolvePermission() {
    if (authorization.hasPermission("excepciones:item:resolver")
        || authorization.hasPermission("expedientes:caso:escribir")) {
      return;
    }
    authorization.requirePermission("excepciones:item:resolver");
  }
}
