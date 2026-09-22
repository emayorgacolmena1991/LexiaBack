package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CatalogAdminService {

  private final CatalogRepository catalogs;
  private final CatalogItemRepository items;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;

  public CatalogAdminService(
      CatalogRepository catalogs,
      CatalogItemRepository items,
      AuthorizationService authorization,
      AuditEventRepository auditEvents) {
    this.catalogs = catalogs;
    this.items = items;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public List<AdminDtos.CatalogSummary> listCatalogs() {
    requireCatalogRead();
    UUID tenantId = AuthContext.require().tenantId();
    return catalogs.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId).stream()
        .map(
            catalog ->
                new AdminDtos.CatalogSummary(
                    catalog.getId(),
                    catalog.getCode(),
                    catalog.getName(),
                    items.countByCatalogIdAndTenantId(catalog.getId(), tenantId),
                    items.countByCatalogIdAndTenantIdAndActiveTrue(catalog.getId(), tenantId)))
        .toList();
  }

  @Transactional(readOnly = true)
  public AdminDtos.CatalogDetail getCatalog(UUID catalogId) {
    requireCatalogRead();
    UUID tenantId = AuthContext.require().tenantId();
    Catalog catalog = requireCatalog(catalogId, tenantId);
    List<AdminDtos.CatalogItemDetail> catalogItems =
        items.findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(catalog.getId(), tenantId)
            .stream()
            .map(this::toItemDetail)
            .toList();
    return new AdminDtos.CatalogDetail(
        catalog.getId(), catalog.getCode(), catalog.getName(), catalogItems);
  }

  @Transactional
  public AdminDtos.CatalogDetail createCatalog(AdminDtos.CreateCatalogRequest request) {
    requireCatalogWrite();
    UUID tenantId = AuthContext.require().tenantId();
    String code = request.code().trim().toUpperCase();
    if (catalogs.findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, code).isPresent()) {
      throw new AuthException(HttpStatus.CONFLICT, "CATALOG_EXISTS", "Ya existe un catálogo con ese código.");
    }
    Catalog catalog = catalogs.save(Catalog.create(tenantId, code, request.name()));
    audit("admin.catalog.created", "catalog", catalog.getId(), "OK");
    return new AdminDtos.CatalogDetail(catalog.getId(), catalog.getCode(), catalog.getName(), List.of());
  }

  @Transactional
  public AdminDtos.CatalogItemDetail createItem(
      UUID catalogId, AdminDtos.CreateCatalogItemRequest request) {
    requireCatalogWrite();
    UUID tenantId = AuthContext.require().tenantId();
    Catalog catalog = requireCatalog(catalogId, tenantId);
    String code = request.code().trim().toUpperCase();
    if (items.existsByCatalogIdAndTenantIdAndCodeIgnoreCase(catalog.getId(), tenantId, code)) {
      throw new AuthException(
          HttpStatus.CONFLICT, "CATALOG_ITEM_EXISTS", "Ya existe un ítem con ese código.");
    }
    CatalogItem item =
        items.save(
            CatalogItem.create(
                tenantId, catalog.getId(), code, request.label(), request.sortOrder()));
    audit("admin.catalog.item_created", "catalog_item", item.getId(), "OK");
    return toItemDetail(item);
  }

  @Transactional
  public AdminDtos.CatalogItemDetail updateItem(
      UUID catalogId, UUID itemId, AdminDtos.UpdateCatalogItemRequest request) {
    requireCatalogWrite();
    UUID tenantId = AuthContext.require().tenantId();
    requireCatalog(catalogId, tenantId);
    CatalogItem item = requireItem(itemId, tenantId, catalogId);
    item.update(request.label(), request.sortOrder(), request.active());
    items.save(item);
    audit("admin.catalog.item_updated", "catalog_item", item.getId(), "OK");
    return toItemDetail(item);
  }

  @Transactional
  public void deleteItem(UUID catalogId, UUID itemId) {
    requireCatalogWrite();
    UUID tenantId = AuthContext.require().tenantId();
    requireCatalog(catalogId, tenantId);
    CatalogItem item = requireItem(itemId, tenantId, catalogId);
    item.update(item.getLabel(), item.getSortOrder(), false);
    items.save(item);
    audit("admin.catalog.item_deactivated", "catalog_item", item.getId(), "OK");
  }

  private Catalog requireCatalog(UUID catalogId, UUID tenantId) {
    return catalogs
        .findByIdAndTenantIdAndDeletedAtIsNull(catalogId, tenantId)
        .orElseThrow(
            () ->
                new AuthException(HttpStatus.NOT_FOUND, "CATALOG_NOT_FOUND", "Catálogo no encontrado."));
  }

  private CatalogItem requireItem(UUID itemId, UUID tenantId, UUID catalogId) {
    CatalogItem item =
        items
            .findByIdAndTenantId(itemId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "CATALOG_ITEM_NOT_FOUND", "Ítem no encontrado."));
    if (!item.getCatalogId().equals(catalogId)) {
      throw new AuthException(HttpStatus.NOT_FOUND, "CATALOG_ITEM_NOT_FOUND", "Ítem no encontrado.");
    }
    return item;
  }

  private AdminDtos.CatalogItemDetail toItemDetail(CatalogItem item) {
    return new AdminDtos.CatalogItemDetail(
        item.getId(), item.getCode(), item.getLabel(), item.getSortOrder(), item.isActive());
  }

  private void requireCatalogRead() {
    if (authorization.hasPermission("admin:catalogos:escribir")
        || authorization.hasPermission("admin:tenant:leer")
        || authorization.hasPermission("admin:usuarios:leer")) {
      return;
    }
    authorization.requirePermission("admin:catalogos:escribir");
  }

  private void requireCatalogWrite() {
    authorization.requirePermission("admin:catalogos:escribir");
  }

  private void audit(String event, String objectType, UUID objectId, String result) {
    var principal = AuthContext.get();
    UUID tenantId = principal != null ? principal.tenantId() : null;
    UUID actor = principal != null ? principal.userId() : null;
    HttpServletRequest request = currentRequest();
    String ip = request != null ? clientIp(request) : null;
    String userAgent = request != null ? request.getHeader("User-Agent") : null;
    auditEvents.save(AuditEvent.of(tenantId, actor, event, objectType, objectId, result, ip, userAgent));
  }

  private static HttpServletRequest currentRequest() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
      return servletRequestAttributes.getRequest();
    }
    return null;
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
