package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.PlantillaItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoItem;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.documentos.DocumentRequirementRepository;
import com.lexia.api.modules.expedientes.proceso.ProductTemplateRepository;
import com.lexia.api.modules.expedientes.requisitos.RequisitosService;
import com.lexia.api.modules.expedientes.requisitos.RequisitosService.Resolucion;
import com.lexia.api.modules.expedientes.requisitos.RequisitosService.TipoDocumental;

@Service
public class ProductoBiessService {

  public static final String CATALOG_PRODUCTO = "ESC_PRODUCTO";

  private final CatalogRepository catalogs;
  private final CatalogItemRepository items;
  private final DocumentRequirementRepository requirements;
  private final ProductTemplateRepository templates;
  private final RequisitosService requisitos;

  public ProductoBiessService(
      CatalogRepository catalogs,
      CatalogItemRepository items,
      DocumentRequirementRepository requirements,
      ProductTemplateRepository templates,
      RequisitosService requisitos) {
    this.catalogs = catalogs;
    this.items = items;
    this.requirements = requirements;
    this.templates = templates;
    this.requisitos = requisitos;
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

  /** Requisitos generales del producto y cantón (sin condiciones del caso): paso 1 del wizard. */
  @Transactional(readOnly = true)
  public ProductoDetalle detalle(String productCode, String canton) {
    UUID tenantId = AuthContext.require().tenantId();
    CatalogItem producto = requireProducto(tenantId, productCode);
    List<DocumentoRequisitoItem> docs =
        aItems(requisitos.resolver(tenantId, producto.getCode(), canton, List.of()));
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

  /**
   * Requisitos del borrador o expediente con sus condiciones confirmadas. Entrada única para
   * carga, IA, ingesta, wizard y ficha ({@link RequisitosService}).
   */
  @Transactional(readOnly = true)
  public Resolucion resolverRequisitos(
      UUID tenantId, UUID expedienteId, String productCode, String canton) {
    return requisitos.resolverExpediente(tenantId, expedienteId, productCode, canton);
  }

  /** Catálogo completo de tipos de escrituración del tenant (sin OTRO). */
  @Transactional(readOnly = true)
  public List<TipoDocumental> catalogoTipos(UUID tenantId) {
    return requisitos.catalogoTipos(tenantId);
  }

  public static List<DocumentoRequisitoItem> aItems(Resolucion resolucion) {
    if (resolucion == null) {
      return List.of();
    }
    return resolucion.requisitos().stream()
        .map(
            r ->
                new DocumentoRequisitoItem(
                    r.tipo(),
                    r.descripcion(),
                    r.obligatorio(),
                    r.maxValidityDays(),
                    r.canton(),
                    r.sortOrder(),
                    r.esencial(),
                    r.porPersona(),
                    r.rolPersona(),
                    r.condicion()))
        .toList();
  }

  /**
   * Código → nombre visible de los productos del tenant (dos consultas). Sin catálogo, vacío: quien
   * lo usa muestra el código.
   */
  @Transactional(readOnly = true)
  public java.util.Map<String, String> etiquetas(UUID tenantId) {
    java.util.Map<String, String> out = new java.util.HashMap<>();
    catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOG_PRODUCTO)
        .ifPresent(
            c ->
                items
                    .findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(c.getId(), tenantId)
                    .forEach(p -> out.put(p.getCode(), p.getLabel())));
    return out;
  }

  public CatalogItem requireProducto(UUID tenantId, String productCode) {
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

  public static String normalizeCanton(String canton) {
    return RequisitosService.normalizarCanton(canton);
  }
}
