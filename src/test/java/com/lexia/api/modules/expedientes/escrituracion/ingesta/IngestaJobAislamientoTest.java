package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaValidacionIaWorker;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Cada tick pide solo sus tipos, y el lease vencido usa el mismo filtro. */
class IngestaJobAislamientoTest {

  private final UUID tenant = UUID.randomUUID();

  @SuppressWarnings("unchecked")
  private IngestaJobWorker worker(IngestaJobQueue cola, IngestaMasivaEscrituracionService ingesta,
      CoactivaExpedienteAnalisisWorker diagnostico) {
    ObjectProvider<CoactivaExpedienteAnalisisWorker> d = mock(ObjectProvider.class);
    ObjectProvider<CoactivaValidacionIaWorker> a = mock(ObjectProvider.class);
    ObjectProvider<IaAnalysisService> e = mock(ObjectProvider.class);
    when(d.getIfAvailable()).thenReturn(diagnostico);
    return new IngestaJobWorker(
        cola, ingesta, d, a, e, mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(BatchEventPublisherService.class), new ObjectMapper());
  }

  @Test
  void escrituracionNoReclamaJobsDeCoactivas() {
    IngestaJobQueue cola = mock(IngestaJobQueue.class);
    IngestaMasivaEscrituracionService ingesta = mock(IngestaMasivaEscrituracionService.class);
    CoactivaExpedienteAnalisisWorker diagnostico = mock(CoactivaExpedienteAnalisisWorker.class);
    when(cola.reclamar(anyString(), any())).thenAnswer(inv -> {
      Collection<?> tipos = inv.getArgument(1);
      if (!tipos.contains("OCR_EXTRACTION") || tipos.contains("COACTIVA_DIAGNOSTICO")) {
        return Optional.empty();
      }
      return Optional.of(new IngestaJobQueue.Job(
          UUID.randomUUID(), tenant, "OCR_EXTRACTION", "{\"batchId\":\"" + UUID.randomUUID() + "\"}", 1));
    });

    worker(cola, ingesta, diagnostico).tick();

    ArgumentCaptor<Collection<String>> tipos = ArgumentCaptor.forClass(Collection.class);
    verify(cola, timeout(2000).times(2)).reclamar(anyString(), tipos.capture());
    assertTrue(tipos.getAllValues().stream().anyMatch(pedido ->
        Set.copyOf(pedido).equals(Set.copyOf(IngestaJobWorker.TIPOS_ESCRITURACION))));
    assertTrue(tipos.getAllValues().stream()
        .filter(pedido -> pedido.contains("OCR_EXTRACTION"))
        .noneMatch(pedido -> pedido.contains("COACTIVA_DIAGNOSTICO") || pedido.contains("COACTIVA_VALIDACION_ANEXO")));
    verify(ingesta, timeout(2000)).ejecutarJob(any());
    verify(diagnostico, never()).ejecutar(any(), any());
  }

  @Test
  void coactivasNoReclamaJobsDeEscrituracion() {
    IngestaJobQueue cola = mock(IngestaJobQueue.class);
    IngestaMasivaEscrituracionService ingesta = mock(IngestaMasivaEscrituracionService.class);
    CoactivaExpedienteAnalisisWorker diagnostico = mock(CoactivaExpedienteAnalisisWorker.class);
    UUID archivo = UUID.randomUUID();
    when(cola.reclamar(anyString(), any())).thenAnswer(inv -> {
      Collection<?> tipos = inv.getArgument(1);
      if (!tipos.contains("COACTIVA_DIAGNOSTICO") || tipos.contains("ESCRITURACION_ANALISIS")) {
        return Optional.empty();
      }
      return Optional.of(new IngestaJobQueue.Job(
          UUID.randomUUID(), tenant, "COACTIVA_DIAGNOSTICO",
          "{\"archivoId\":\"" + archivo + "\",\"expedienteId\":\"" + UUID.randomUUID() + "\"}", 1));
    });

    worker(cola, ingesta, diagnostico).tick();

    ArgumentCaptor<Collection<String>> tipos = ArgumentCaptor.forClass(Collection.class);
    verify(cola, timeout(2000).times(2)).reclamar(anyString(), tipos.capture());
    assertTrue(tipos.getAllValues().stream().anyMatch(pedido ->
        Set.copyOf(pedido).equals(Set.copyOf(IngestaJobWorker.TIPOS_COACTIVA))));
    assertTrue(tipos.getAllValues().stream()
        .filter(pedido -> pedido.contains("COACTIVA_DIAGNOSTICO"))
        .noneMatch(pedido -> pedido.contains("ESCRITURACION_ANALISIS")
            || pedido.contains("OCR_EXTRACTION")
            || pedido.contains("IA_ANALYSIS")));
    verify(diagnostico, timeout(2000)).ejecutar(tenant, archivo);
    verify(ingesta, never()).ejecutarJob(any());
  }

  @Test
  void leaseVencidoUsaElMismoFiltroDeTipo() {
    EntityManager em = mock(EntityManager.class);
    Query query = mock(Query.class);
    when(em.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.getResultList()).thenReturn(List.of());

    new IngestaJobQueue(em).reclamar("nodo", List.of("ESCRITURACION_ANALISIS", "OCR_EXTRACTION", "IA_ANALYSIS"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(em).createNativeQuery(sql.capture());
    String q = sql.getValue();
    int filtro = q.indexOf("tipo IN (:tipos)");
    int pendiente = q.indexOf("estado = 'PENDIENTE'");
    int enProceso = q.indexOf("estado = 'EN_PROCESO'");
    assertTrue(filtro >= 0 && filtro < pendiente && filtro < enProceso);
    assertTrue(q.indexOf("locked_until < now()") > enProceso);
    verify(query).setParameter("tipos", List.of("ESCRITURACION_ANALISIS", "OCR_EXTRACTION", "IA_ANALYSIS"));
  }
}
