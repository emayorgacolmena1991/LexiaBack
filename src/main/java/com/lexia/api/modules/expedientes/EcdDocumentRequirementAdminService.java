package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.EcdDocumentAdminDtos.DocumentRequirementsView;
import com.lexia.api.modules.expedientes.EcdDocumentAdminDtos.ReplaceDocumentsRequest;
import com.lexia.api.modules.expedientes.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class EcdDocumentRequirementAdminService {

  private static final String CATALOG_DOC_TYPE = "DOC_TYPE";

  private final CatalogRepository catalogs;
  private final CatalogItemRepository catalogItems;
  private final EcdDocumentReqRepository documentReqs;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeService configChanges;

  public EcdDocumentRequirementAdminService(
      CatalogRepository catalogs,
      CatalogItemRepository catalogItems,
      EcdDocumentReqRepository documentReqs,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeService configChanges) {
    this.catalogs = catalogs;
    this.catalogItems = catalogItems;
    this.documentReqs = documentReqs;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public DocumentRequirementsView list() {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    List<CatalogItemRef> documentTypes = loadDocumentTypes(tenantId);
    List<CatalogItemRef> required = buildRequired(tenantId, documentTypes);
    return new DocumentRequirementsView(documentTypes, required);
  }

  @Transactional
  public DocumentRequirementsView replace(ReplaceDocumentsRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    DocumentRequirementsView current = list();
    LinkedHashSet<String> allowed =
        current.documentTypes().stream()
            .map(CatalogItemRef::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : request.documentTypeCodes()) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(code)) {
        throw new AuthException(
            HttpStatus.BAD_REQUEST, "INVALID_DOC_TYPE", "Tipo documental no reconocido: " + code);
      }
      normalized.add(code);
    }

    documentReqs.deleteByTenantId(tenantId);
    int order = 1;
    for (String code : normalized) {
      documentReqs.save(EcdDocumentReq.create(tenantId, code, order++));
    }

    configChanges.markDraftByCaseType(
        tenantId,
        "ECD",
        ChangeSetDomain.DOCUMENTS,
        "Checklist documental coactivas",
        "ecd_document_req");
    audit();
    return list();
  }

  private List<CatalogItemRef> buildRequired(UUID tenantId, List<CatalogItemRef> documentTypes) {
    Map<String, String> labels =
        documentTypes.stream()
            .collect(Collectors.toMap(CatalogItemRef::code, CatalogItemRef::label, (a, b) -> a));
    return documentReqs.findByTenantIdOrderBySortOrderAsc(tenantId).stream()
        .map(
            row -> {
              String code = row.getDocumentTypeCode();
              return new CatalogItemRef(code, labels.getOrDefault(code, code));
            })
        .toList();
  }

  private List<CatalogItemRef> loadDocumentTypes(UUID tenantId) {
    return catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOG_DOC_TYPE)
        .map(
            catalog ->
                catalogItems
                    .findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(catalog.getId(), tenantId)
                    .stream()
                    .map(item -> new CatalogItemRef(item.getCode(), item.getLabel()))
                    .toList())
        .orElse(List.of());
  }

  private void requireProcessRead() {
    if (authorization.hasPermission("admin:proceso:leer")
        || authorization.hasPermission("admin:proceso:escribir")
        || authorization.hasPermission("admin:tenant:leer")) {
      return;
    }
    authorization.requirePermission("admin:proceso:leer");
  }

  private void audit() {
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            "admin.ecd.documents.updated",
            "ecd_document_req",
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
