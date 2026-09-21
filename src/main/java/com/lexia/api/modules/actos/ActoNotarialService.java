package com.lexia.api.modules.actos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialListadoDTO;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.actos.ActosDtos.DocumentoRequeridoDTO;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActoNotarialService {

  static final String CATALOG_ACTO = "ESC_OPERATION_TYPE";
  static final String CATALOG_ACTO_ALT = "ACTO_NOTARIAL";

  private final CatalogRepository catalogs;
  private final CatalogItemRepository items;
  private final ActRequirementRepository requirements;

  public ActoNotarialService(
      CatalogRepository catalogs,
      CatalogItemRepository items,
      ActRequirementRepository requirements) {
    this.catalogs = catalogs;
    this.items = items;
    this.requirements = requirements;
  }

  @Transactional(readOnly = true)
  public List<ActoNotarialListadoDTO> listarActos() {
    UUID tenantId = AuthContext.require().tenantId();
    Catalog catalogo = catalogoActos(tenantId);
    return items
        .findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(catalogo.getId(), tenantId)
        .stream()
        .filter(CatalogItem::isActive)
        .map(
            acto ->
                new ActoNotarialListadoDTO(
                    acto.getCode(),
                    acto.getLabel(),
                    (int) requirements.countByTenantIdAndActItemId(tenantId, acto.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public ActoNotarialRespuestaDTO obtenerRequisitosPorActo(String idActo) {
    if (idActo == null || idActo.isBlank()) {
      throw ApiException.notFound("El acto notarial seleccionado no es válido.");
    }
    UUID tenantId = AuthContext.require().tenantId();
    Catalog catalogo = catalogoActos(tenantId);
    CatalogItem acto =
        items
            .findByCatalogIdAndTenantIdAndCodeIgnoreCaseAndActiveTrue(
                catalogo.getId(), tenantId, idActo.trim())
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "El acto notarial seleccionado no es válido: " + idActo));

    List<ActRequirement> filas =
        requirements.findByTenantIdAndActItemIdOrderBySortOrderAsc(tenantId, acto.getId());
    if (filas.isEmpty()) {
      return new ActoNotarialRespuestaDTO(acto.getCode(), acto.getLabel(), List.of());
    }
    List<UUID> docIds = filas.stream().map(ActRequirement::getDocumentItemId).toList();
    Map<UUID, CatalogItem> docs =
        items.findByTenantIdAndIdIn(tenantId, docIds).stream()
            .collect(Collectors.toMap(CatalogItem::getId, Function.identity()));

    List<DocumentoRequeridoDTO> documentos =
        filas.stream()
            .map(
                fila -> {
                  CatalogItem doc = docs.get(fila.getDocumentItemId());
                  String codigo = doc != null ? doc.getCode() : fila.getDocumentItemId().toString();
                  String nombre = doc != null ? doc.getLabel() : codigo;
                  return new DocumentoRequeridoDTO(
                      codigo, nombre, fila.isRequired(), fila.getDescription());
                })
            .toList();

    return new ActoNotarialRespuestaDTO(acto.getCode(), acto.getLabel(), documentos);
  }

  private Catalog catalogoActos(UUID tenantId) {
    return catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOG_ACTO)
        .or(() -> catalogs.findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, CATALOG_ACTO_ALT))
        .orElseThrow(() -> ApiException.notFound("Catálogo de actos notariales no configurado."));
  }
}
