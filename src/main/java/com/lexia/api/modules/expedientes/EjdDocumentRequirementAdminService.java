package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.expedientes.TenantConfigDtos.OperationDocuments;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdDocumentRequirementAdminService {

  private final TenantConfigService tenantConfigService;
  private final EjdOperationDocumentReqRepository operationDocumentReqs;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeService configChanges;

  public EjdDocumentRequirementAdminService(
      TenantConfigService tenantConfigService,
      EjdOperationDocumentReqRepository operationDocumentReqs,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeService configChanges) {
    this.tenantConfigService = tenantConfigService;
    this.operationDocumentReqs = operationDocumentReqs;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public EjdDocumentAdminDtos.DocumentRequirementsView list() {
    return tenantConfigService.getEjdDocumentRequirementsForAdmin();
  }

  @Transactional
  public EjdDocumentAdminDtos.DocumentRequirementsView replaceForOperation(
      String operationCodeParam, EjdDocumentAdminDtos.ReplaceOperationDocumentsRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    String operationCode = normalizeOperationCode(operationCodeParam);

    EjdDocumentAdminDtos.DocumentRequirementsView current = list();
    validateOperation(operationCode, current.operationTypes());
    Set<String> allowedDocs =
        current.documentTypes().stream()
            .map(CatalogItemRef::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<String> codes = request.documentTypeCodes() == null ? List.of() : request.documentTypeCodes();
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : codes) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowedDocs.contains(code)) {
        throw new AuthException(
            HttpStatus.BAD_REQUEST,
            "INVALID_DOC_TYPE",
            "Tipo documental no reconocido: " + code);
      }
      normalized.add(code);
    }

    operationDocumentReqs.deleteByTenantIdAndOperationCode(tenantId, operationCode);
    int order = 1;
    for (String docCode : normalized) {
      operationDocumentReqs.save(
          EjdOperationDocumentReq.create(tenantId, operationCode, docCode, order++));
    }

    configChanges.markDraftByCaseType(
        tenantId,
        "EJD",
        ChangeSetDomain.DOCUMENTS,
        "Matriz documental EJD (" + operationCode + ")",
        operationCode);
    audit("admin.ejd.documents.updated", operationCode);
    return list();
  }

  private static void validateOperation(String operationCode, List<CatalogItemRef> operationTypes) {
    boolean known =
        operationTypes.stream().anyMatch(item -> operationCode.equals(item.code()));
    if (!known) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "INVALID_OPERATION", "Tipo de operación no reconocido: " + operationCode);
    }
  }

  private static String normalizeOperationCode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_OPERATION", "Operación requerida.");
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  private void audit(String action, String operationCode) {
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "ejd_operation_document_req",
            null,
            "OK",
            http != null ? http.getRemoteAddr() : null,
            http != null ? http.getHeader("User-Agent") : null));
  }

  private static HttpServletRequest currentRequest() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest();
    }
    return null;
  }
}
