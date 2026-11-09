package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lexia.api.modules.expedientes.coactivas.jobs.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.coactivas.jobs.CoactivaJobQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tres replaces del mismo expediente dejan un solo job vivo.
 * Semántica de {@link CoactivaJobQueue#encolarSiActivo}: si hay PENDIENTE, reescribe el payload
 * y no inserta otra fila. No cancela.
 */
class CoactivaDiagnosticoNoApilaJobsTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-0000000000b4");

  @Test
  void tresReemplazosDejanUnSoloJobActivo() {
    Cola cola = new Cola();
    CoactivaExpedienteAnalisisWorker worker =
        new CoactivaExpedienteAnalisisWorker(
            null, null, null, null, null, null, null, null, null, null, null,
            cola, mock(BatchEventPublisherService.class), null, null, false);
    UUID expediente = UUID.randomUUID();
    UUID pdf1 = UUID.randomUUID();
    UUID pdf2 = UUID.randomUUID();
    UUID pdf3 = UUID.randomUUID();

    worker.encolar(TENANT, expediente, pdf1);
    worker.encolar(TENANT, expediente, pdf2);
    worker.encolar(TENANT, expediente, pdf3);

    List<Cola.Fila> activos = cola.activos(TENANT, "COACTIVA_DIAGNOSTICO", expediente.toString());
    assertEquals(1, activos.size(), "jobs activos: " + cola.filas.size());
    assertEquals(1, cola.filas.size());
    assertTrue(activos.get(0).payload.contains(pdf3.toString()));
  }

  /** Misma regla que {@code CoactivaJobQueue.encolarSiActivo}, en memoria. */
  static final class Cola extends CoactivaJobQueue {
    static final class Fila {
      final UUID id;
      final UUID tenantId;
      final String tipo;
      final String expedienteId;
      String payload;
      final String estado;

      Fila(UUID id, UUID tenantId, String tipo, String expedienteId, String payload, String estado) {
        this.id = id;
        this.tenantId = tenantId;
        this.tipo = tipo;
        this.expedienteId = expedienteId;
        this.payload = payload;
        this.estado = estado;
      }
    }

    final List<Fila> filas = new ArrayList<>();

    Cola() {
      super(null);
    }

    @Override
    public UUID encolar(UUID tenantId, String tipo, String payload) {
      UUID id = UUID.randomUUID();
      filas.add(new Fila(id, tenantId, tipo, expedienteDe(payload), payload, "PENDIENTE"));
      return id;
    }

    @Override
    public UUID encolarSiActivo(UUID tenantId, String tipo, String expedienteId, String payload) {
      for (int i = filas.size() - 1; i >= 0; i--) {
        Fila fila = filas.get(i);
        if (!fila.tenantId.equals(tenantId)
            || !fila.tipo.equals(tipo)
            || !expedienteId.equals(fila.expedienteId)
            || (!"PENDIENTE".equals(fila.estado) && !"EN_PROCESO".equals(fila.estado))) {
          continue;
        }
        if ("PENDIENTE".equals(fila.estado)) {
          fila.payload = payload;
        }
        return fila.id;
      }
      return encolar(tenantId, tipo, payload);
    }

    List<Fila> activos(UUID tenantId, String tipo, String expedienteId) {
      List<Fila> out = new ArrayList<>();
      for (Fila fila : filas) {
        if (fila.tenantId.equals(tenantId)
            && fila.tipo.equals(tipo)
            && expedienteId.equals(fila.expedienteId)
            && ("PENDIENTE".equals(fila.estado) || "EN_PROCESO".equals(fila.estado))) {
          out.add(fila);
        }
      }
      return out;
    }

    private static String expedienteDe(String payload) {
      String key = "\"expedienteId\":\"";
      int i = payload.indexOf(key);
      int start = i + key.length();
      return payload.substring(start, payload.indexOf('"', start));
    }
  }
}
