package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatosBiessStoreTest {

  @Mock private ExtractedDataRepository extractedData;

  private DatosBiessStore store;

  @BeforeEach
  void setUp() {
    store = new DatosBiessStore(extractedData);
  }

  @Test
  void guardarReemplazaGrupoBiess() {
    UUID tenant = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();

    store.guardar(tenant, caseId, new DatosBiessMinuta("120000", "5.99", "240", "453.48", "ANDRE"));

    verify(extractedData)
        .deleteByCaseIdAndTenantIdAndFieldGroupIn(caseId, tenant, Set.of(DatosBiessStore.GRUPO));
    ArgumentCaptor<ExtractedData> captor = ArgumentCaptor.forClass(ExtractedData.class);
    verify(extractedData, org.mockito.Mockito.times(5)).save(captor.capture());
    assertEquals("120000", valor(captor, "biess.monto"));
    assertEquals("5.99", valor(captor, "biess.tasa"));
    assertEquals("240", valor(captor, "biess.plazo"));
    assertEquals("453.48", valor(captor, "biess.cuota"));
    assertEquals("ANDRE", valor(captor, "biess.apoderado"));
  }

  @Test
  void capturaLegadaSinCuotaSigueCargando() {
    UUID tenant = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    when(extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenant, DatosBiessStore.GRUPO))
        .thenReturn(List.of(ExtractedData.create(tenant, caseId, "biess.monto", "120000", "biess")));

    DatosBiessMinuta datos = store.cargar(caseId, tenant);

    assertEquals("120000", datos.monto());
    assertEquals("", datos.cuota());
  }

  @Test
  void cargarDevuelveNullSiNoHayFilas() {
    UUID tenant = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    when(extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenant, DatosBiessStore.GRUPO))
        .thenReturn(List.of());

    assertNull(store.cargar(caseId, tenant));
  }

  @Test
  void cargarReconstruyeCaptura() {
    UUID tenant = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    when(extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenant, DatosBiessStore.GRUPO))
        .thenReturn(
            List.of(
                ExtractedData.create(tenant, caseId, "biess.monto", "120000", "biess"),
                ExtractedData.create(tenant, caseId, "biess.tasa", "5.99", "biess"),
                ExtractedData.create(tenant, caseId, "biess.plazo", "240", "biess"),
                ExtractedData.create(tenant, caseId, "biess.apoderado", "ANDRE", "biess")));

    DatosBiessMinuta datos = store.cargar(caseId, tenant);

    assertEquals("120000", datos.monto());
    assertEquals("5.99", datos.tasa());
    assertEquals("240", datos.plazo());
    assertEquals("ANDRE", datos.apoderado());
  }

  private static String valor(ArgumentCaptor<ExtractedData> captor, String label) {
    return captor.getAllValues().stream()
        .filter(row -> label.equals(row.getFieldLabel()))
        .map(ExtractedData::getFieldValue)
        .findFirst()
        .orElseThrow();
  }
}
