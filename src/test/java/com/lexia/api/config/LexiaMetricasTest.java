package com.lexia.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaJobWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/** P0: gauges propios y acceso a /actuator/metrics solo desde loopback. */
class LexiaMetricasTest {

  @Test
  @SuppressWarnings("unchecked")
  void registraGaugesDeColaWorkersYMemoria() {
    IngestaJobWorker worker = mock(IngestaJobWorker.class);
    when(worker.hilosOcupados()).thenReturn(3);
    ObjectProvider<IngestaJobWorker> ingesta = mock(ObjectProvider.class);
    when(ingesta.getIfAvailable()).thenReturn(worker);
    LexiaMetricas metricas =
        new LexiaMetricas(
            mock(ObjectProvider.class), ingesta, mock(ObjectProvider.class), mock(ObjectProvider.class),
            mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    metricas.bindTo(registry);

    assertEquals(3.0, registry.get("lexia.worker.hilos.ocupados").tag("worker", "ingesta").gauge().value());
    assertTrue(Double.isNaN(registry.get("lexia.worker.hilos.ocupados").tag("worker", "coactiva").gauge().value()));
    // Sin JdbcTemplate la cola se informa en 0, no rompe el scrape.
    assertEquals(0.0, registry.get("lexia.jobs").tag("tipo", "PROCESAR_DOCUMENTO").tag("estado", "PENDIENTE").gauge().value());
    assertEquals(
        LexiaMetricas.TIPOS.size() * LexiaMetricas.ESTADOS.size(), registry.get("lexia.jobs").gauges().size());
  }

  @Test
  void metricasSoloDesdeLoopback() {
    assertTrue(SecurityConfig.metricasDesdeLoopback(peticion("GET", "/actuator/metrics", "127.0.0.1")));
    assertTrue(SecurityConfig.metricasDesdeLoopback(peticion("GET", "/actuator/metrics/lexia.jobs", "0:0:0:0:0:0:0:1")));
    assertFalse(SecurityConfig.metricasDesdeLoopback(peticion("GET", "/actuator/metrics", "203.0.113.5")));
    assertFalse(SecurityConfig.metricasDesdeLoopback(peticion("POST", "/actuator/metrics", "127.0.0.1")));
    assertFalse(SecurityConfig.metricasDesdeLoopback(peticion("GET", "/actuator/metricsX", "127.0.0.1")));
  }

  private static MockHttpServletRequest peticion(String metodo, String uri, String ip) {
    MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
    req.setRemoteAddr(ip);
    return req;
  }
}
