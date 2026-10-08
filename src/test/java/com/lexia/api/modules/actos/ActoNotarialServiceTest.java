package com.lexia.api.modules.actos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActoNotarialServiceTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID CATALOG_ID = UUID.fromString("f3000000-0000-7000-8000-000000000003");
  private static final UUID ACTO_ID = UUID.fromString("f3100000-0000-7000-8000-000000000001");
  private static final UUID DOC_ID = UUID.fromString("f3200000-0000-7000-8000-000000000001");

  @Mock private CatalogRepository catalogs;
  @Mock private CatalogItemRepository items;
  @Mock private ActRequirementRepository requirements;

  private ActoNotarialService service;

  @BeforeEach
  void setUp() {
    service = new ActoNotarialService(catalogs, items, requirements);
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
  }

  @AfterEach
  void tearDown() {
    AuthContext.clear();
  }

  @Test
  void resuelveMatrizDesdeCatalogo() {
    Catalog catalogo = mock(Catalog.class);
    when(catalogo.getId()).thenReturn(CATALOG_ID);

    CatalogItem acto = mock(CatalogItem.class);
    when(acto.getId()).thenReturn(ACTO_ID);
    when(acto.getCode()).thenReturn("COMPRAVENTA");
    when(acto.getLabel()).thenReturn("Compraventa de Inmueble");

    CatalogItem cedula = mock(CatalogItem.class);
    when(cedula.getId()).thenReturn(DOC_ID);
    when(cedula.getCode()).thenReturn("CEDULA");
    when(cedula.getLabel()).thenReturn("Cédula de Identidad / Ciudadanía");

    ActRequirement fila = mock(ActRequirement.class);
    when(fila.getDocumentItemId()).thenReturn(DOC_ID);
    when(fila.isRequired()).thenReturn(true);
    when(fila.getDescription()).thenReturn("Documento de identidad del comprador y vendedor");

    when(catalogs.findByTenantIdAndCodeAndDeletedAtIsNull(TENANT, "ESC_OPERATION_TYPE"))
        .thenReturn(Optional.of(catalogo));
    when(items.findByCatalogIdAndTenantIdAndCodeIgnoreCaseAndActiveTrue(
            CATALOG_ID, TENANT, "COMPRAVENTA"))
        .thenReturn(Optional.of(acto));
    when(requirements.findByTenantIdAndActItemIdOrderBySortOrderAsc(TENANT, ACTO_ID))
        .thenReturn(List.of(fila));
    when(items.findByTenantIdAndIdIn(TENANT, List.of(DOC_ID))).thenReturn(List.of(cedula));

    ActoNotarialRespuestaDTO dto = service.obtenerRequisitosPorActo("COMPRAVENTA");

    assertEquals("COMPRAVENTA", dto.idActo());
    assertEquals(1, dto.totalRequisitos());
    assertEquals("CEDULA", dto.documentos().get(0).codigoDocumento());
    assertEquals(true, dto.documentos().get(0).obligatorio());
  }

  @Test
  void actoInvalidoLanzaNotFound() {
    Catalog catalogo = mock(Catalog.class);
    when(catalogo.getId()).thenReturn(CATALOG_ID);
    when(catalogs.findByTenantIdAndCodeAndDeletedAtIsNull(TENANT, "ESC_OPERATION_TYPE"))
        .thenReturn(Optional.of(catalogo));
    when(items.findByCatalogIdAndTenantIdAndCodeIgnoreCaseAndActiveTrue(
            CATALOG_ID, TENANT, "FOO"))
        .thenReturn(Optional.empty());

    assertThrows(ApiException.class, () -> service.obtenerRequisitosPorActo("FOO"));
  }
}
