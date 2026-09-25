package com.lexia.api.modules.expedientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.EscrituracionDtos.PlantillaItem;
import com.lexia.api.modules.expedientes.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.EscrituracionDtos.ProductoItem;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductoBiessService {

  public static final String CATALOG_PRODUCTO = "ESC_PRODUCTO";

  private final CatalogRepository catalogs;
  private final CatalogItemRepository items;
  private final DocumentRequirementRepository requirements;
  private final ProductTemplateRepository templates;

  public ProductoBiessService(
      CatalogRepository catalogs,
      CatalogItemRepository items,
      DocumentRequirementRepository requirements,
      ProductTemplateRepository templates) {
    this.catalogs = catalogs;
    this.items = items;
    this.requirements = requirements;
    this.templates = templates;
  }

  @Transactional(readOnly = true)
  public List<ProductoItem> listarProductos() {
    UUID tenantId = AuthContext.require().tenantId();
    Catalog catalogo = catalogo(tenantId);
    return items
        .findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(catalogo.getId(), tenantId)
        .stream()
        .filter(CatalogItem::isActive)
        .map(
            p ->
                new ProductoItem(
                    p.getCode(),
                    p.getLabel(),
                    p.getSortOrder(),
                    (int)
                        requirements
                            .findByTenantIdAndProductCodeOrderBySortOrderAsc(tenantId, p.getCode())
                            .stream()
                            .filter(r -> "ALL".equalsIgnoreCase(r.getCanton()))
                            .count()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ProductoDetalle detalle(String productCode, String canton) {
    UUID tenantId = AuthContext.require().tenantId();
    CatalogItem producto = requireProducto(tenantId, productCode);
    String cantonNorm = normalizeCanton(canton);
    List<DocumentoRequisitoItem> docs =
        resolveRequirements(tenantId, producto.getCode(), cantonNorm).stream()
            .map(
                r ->
                    new DocumentoRequisitoItem(
                        r.getDocumentTypeCode(),
                        r.getDescription(),
                        r.isMandatory(),
                        r.getMaxValidityDays(),
                        r.getCanton(),
                        r.getSortOrder()))
            .toList();
    List<PlantillaItem> plantillas =
        templates
            .findByTenantIdAndProductCodeAndActiveTrueOrderBySortOrderAsc(
                tenantId, producto.getCode())
            .stream()
            .map(
                t ->
                    new PlantillaItem(
                        t.getTemplateKind(),
                        t.getLabel(),
                        t.isCompanySuppliesCv(),
                        t.getStorageKey()))
            .toList();
    return new ProductoDetalle(producto.getCode(), producto.getLabel(), docs, plantillas);
  }

  @Transactional(readOnly = true)
  public List<DocumentRequirement> resolveRequirements(
      UUID tenantId, String productCode, String canton) {
    String cantonNorm = normalizeCanton(canton);
    Map<String, DocumentRequirement> byDoc = new LinkedHashMap<>();
    for (DocumentRequirement row :
        requirements.findByTenantIdAndProductCodeOrderBySortOrderAsc(tenantId, productCode)) {
      if ("ALL".equalsIgnoreCase(row.getCanton())) {
        byDoc.putIfAbsent(row.getDocumentTypeCode(), row);
      }
    }
    if (cantonNorm != null && !"ALL".equals(cantonNorm)) {
      for (DocumentRequirement row :
          requirements.findByTenantIdAndProductCodeOrderBySortOrderAsc(tenantId, productCode)) {
        if (cantonNorm.equalsIgnoreCase(row.getCanton())) {
          byDoc.put(row.getDocumentTypeCode(), row);
        }
      }
    }
    return byDoc.values().stream()
        .sorted(Comparator.comparingInt(DocumentRequirement::getSortOrder))
        .toList();
  }

  CatalogItem requireProducto(UUID tenantId, String productCode) {
    if (productCode == null || productCode.isBlank()) {
      throw ApiException.notFound("Producto BIESS no indicado.");
    }
    Catalog catalogo = catalogo(tenantId);
    return items
        .findByCatalogIdAndTenantIdAndCodeIgnoreCaseAndActiveTrue(
            catalogo.getId(), tenantId, productCode.trim())
        .orElseThrow(
            () -> ApiException.notFound("Producto BIESS no válido: " + productCode));
  }

  private Catalog catalogo(UUID tenantId) {
    return catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOG_PRODUCTO)
        .orElseThrow(() -> ApiException.notFound("Catálogo ESC_PRODUCTO no configurado."));
  }

  static String normalizeCanton(String canton) {
    if (canton == null || canton.isBlank()) {
      return "ALL";
    }
    return canton.trim().toUpperCase(Locale.ROOT)
        .replace('Á', 'A')
        .replace('É', 'E')
        .replace('Í', 'I')
        .replace('Ó', 'O')
        .replace('Ú', 'U')
        .replace('Ñ', 'N');
  }
}
