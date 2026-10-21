package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobQueue;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobWorker;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** {@code app.jobs.payload} es jsonb: Postgres lo devuelve como {@code {"archivoId": "…", …}}. */
class CoactivaJobPayloadTest {

  private final UUID tenant = UUID.randomUUID();
  private final UUID expediente = UUID.randomUUID();
  private final UUID archivo = UUID.randomUUID();
  private final IngestaJobQueue cola = mock(IngestaJobQueue.class);
  private final BatchEventPublisherService sse = mock(BatchEventPublisherService.class);
  private final CoactivaExpedienteAnalisisWorker diagnostico = mock(CoactivaExpedienteAnalisisWorker.class);
  private final CoactivaValidacionIaWorker anexo = mock(CoactivaValidacionIaWorker.class);

  @SuppressWarnings("unchecked")
  private IngestaJobWorker worker() {
    ObjectProvider<CoactivaExpedienteAnalisisWorker> d = mock(ObjectProvider.class);
    ObjectProvider<CoactivaValidacionIaWorker> a = mock(ObjectProvider.class);
    when(d.getIfAvailable()).thenReturn(diagnostico);
    when(a.getIfAvailable()).thenReturn(anexo);
    return new IngestaJobWorker(cola, d, a, sse, new ObjectMapper());
  }

  private String comoJsonb(String payload) {
    return payload.replace("\":\"", "\": \"").replace("\",\"", "\", \"");
  }

  private void reclamar(String tipo, String payload, int intentos) {
    when(cola.reclamar(anyString(), any())).thenAnswer(inv -> {
      Collection<?> tipos = inv.getArgument(1);
      if (!tipos.contains(tipo)) {
        return Optional.empty();
      }
      return Optional.of(new IngestaJobQueue.Job(UUID.randomUUID(), tenant, tipo, payload, intentos));
    });
  }

  @Test
  void elPayloadDelProductorLlegaAlWorkerTrasPasarPorJsonb() {
    IngestaJobQueue productor = mock(IngestaJobQueue.class);
    new CoactivaExpedienteAnalisisWorker(
            null, null, null, null, null, null, null, null, null, null, null, productor, sse, null, null, false)
        .encolar(tenant, expediente, archivo);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(productor).encolar(eq(tenant), eq("COACTIVA_DIAGNOSTICO"), payload.capture());

    reclamar("COACTIVA_DIAGNOSTICO", comoJsonb(payload.getValue()), 1);
    worker().tick();

    verify(diagnostico, timeout(2000)).ejecutar(tenant, archivo);
    verify(cola, timeout(2000)).completar(any(), anyString());
  }

  @Test
  void elAnexoLeeElExpedienteParaNotificarPorSse() {
    reclamar(
        "COACTIVA_VALIDACION_ANEXO",
        "{\"archivoId\": \"" + archivo + "\", \"expedienteId\": \"" + expediente + "\"}",
        1);
    worker().tick();

    verify(anexo, timeout(2000)).ejecutar(tenant, archivo);
    verify(sse, timeout(2000)).publicar(eq(expediente), eq("ANALYSIS_COMPLETED"), any());
  }

  @Test
  void alAgotarReintentosCierraElAnalisisDelArchivoCorrecto() {
    doThrow(new IllegalStateException("Azure caído")).when(diagnostico).ejecutar(tenant, archivo);
    reclamar(
        "COACTIVA_DIAGNOSTICO",
        "{\"archivoId\": \"" + archivo + "\", \"expedienteId\": \"" + expediente + "\"}",
        3);
    worker().tick();

    verify(cola, timeout(2000)).fallar(any(), anyString(), eq("Azure caído"));
    verify(diagnostico, timeout(2000)).abandonar(tenant, archivo, "Azure caído");
  }
}
