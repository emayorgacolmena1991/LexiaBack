package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.lexia.api.modules.expedientes.escrituracion.ingesta.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaJobQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * El reemplazo inserta {@code app.jobs} y avisa por SSE en la misma llamada.
 * No reinicia el contexto de Spring: eso exige la base compartida.
 */
@ExtendWith(MockitoExtension.class)
class CoactivaReemplazoDocumentoIntegrationTest {

  @Mock private IngestaJobQueue jobs;
  @Mock private BatchEventPublisherService sse;

  @Test
  void encolaElDiagnosticoYEmiteAnalysisStarted() {
    CoactivaExpedienteAnalisisWorker worker =
        new CoactivaExpedienteAnalisisWorker(
            null, null, null, null, null, null, null, null, null, null, null,
            jobs, sse, null, null, false);
    UUID tenant = UUID.randomUUID();
    UUID expediente = UUID.randomUUID();
    UUID archivo = UUID.randomUUID();

    worker.encolar(tenant, expediente, archivo);

    verify(jobs).encolar(eq(tenant), eq("COACTIVA_DIAGNOSTICO"), contains(expediente.toString()));
    verify(jobs).encolar(eq(tenant), eq("COACTIVA_DIAGNOSTICO"), contains(archivo.toString()));
    verify(sse).publicar(eq(expediente), eq("ANALYSIS_STARTED"), any());
  }
}
