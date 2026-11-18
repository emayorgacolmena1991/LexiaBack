package com.lexia.api.config;

import com.lexia.api.modules.auth.SessionCache;
import com.lexia.api.modules.expedientes.coactivas.jobs.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.coactivas.jobs.CoactivaJobWorker;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaJobWorker;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaEscrituracionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P0: métricas propias para medir antes/después. Se leen en /actuator/metrics (solo localhost).
 *
 * <ul>
 *   <li>lexia.jobs{tipo,estado}: jobs PENDIENTE y EN_PROCESO ahora; ERROR creados en 24 h.
 *   <li>lexia.jobs.espera.segundos{tipo}: antigüedad del PENDIENTE más viejo (latencia de cola).
 *   <li>lexia.worker.hilos.ocupados{worker}: hilos en uso de cada worker.
 *   <li>lexia.memoria.*, lexia.sse.oyentes{flujo}: estado en memoria que crece con el uso.
 * </ul>
 *
 * Duración de cada job: timer lexia.jobs.duracion{worker,tipo,resultado} (en los workers). Pool de
 * conexiones: hikaricp.connections.*; executors: executor.*{name}.
 */
@Component
public class LexiaMetricas implements MeterBinder {

  private static final Logger LOG = LoggerFactory.getLogger(LexiaMetricas.class);

  static final List<String> TIPOS =
      List.of(
          "PROCESAR_DOCUMENTO",
          "PROCESAR_IA_EXPEDIENTE",
          "COACTIVA_DIAGNOSTICO",
          "COACTIVA_VALIDACION_ANEXO");
  static final List<String> ESTADOS = List.of("PENDIENTE", "EN_PROCESO", "ERROR");

  /** Una lectura de app.jobs sirve a todos los gauges de la cola durante este tiempo. */
  static final Duration VIGENCIA = Duration.ofSeconds(15);

  private final ObjectProvider<JdbcTemplate> jdbc;
  private final ObjectProvider<IngestaJobWorker> ingestaWorker;
  private final ObjectProvider<CoactivaJobWorker> coactivaWorker;
  private final ObjectProvider<CargaDocumentoService> carga;
  private final ObjectProvider<SessionCache> sesiones;
  private final ObjectProvider<IngestaMasivaEscrituracionService> ingesta;
  private final ObjectProvider<BatchEventPublisherService> sseCoactivas;
  private final Clock clock;

  private volatile Foto foto = new Foto(Instant.EPOCH, Map.of(), Map.of());

  public LexiaMetricas(
      ObjectProvider<JdbcTemplate> jdbc,
      ObjectProvider<IngestaJobWorker> ingestaWorker,
      ObjectProvider<CoactivaJobWorker> coactivaWorker,
      ObjectProvider<CargaDocumentoService> carga,
      ObjectProvider<SessionCache> sesiones,
      ObjectProvider<IngestaMasivaEscrituracionService> ingesta,
      ObjectProvider<BatchEventPublisherService> sseCoactivas) {
    this.jdbc = jdbc;
    this.ingestaWorker = ingestaWorker;
    this.coactivaWorker = coactivaWorker;
    this.carga = carga;
    this.sesiones = sesiones;
    this.ingesta = ingesta;
    this.sseCoactivas = sseCoactivas;
    this.clock = Clock.systemUTC();
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String tipo : TIPOS) {
      for (String estado : ESTADOS) {
        Gauge.builder("lexia.jobs", this, m -> m.vigente().cuenta(tipo, estado))
            .tag("tipo", tipo)
            .tag("estado", estado)
            .description("Jobs en app.jobs (ERROR: creados en las últimas 24 h)")
            .register(registry);
      }
      Gauge.builder("lexia.jobs.espera.segundos", this, m -> m.vigente().espera(tipo))
          .tag("tipo", tipo)
          .description("Antigüedad del job PENDIENTE más viejo")
          .baseUnit("seconds")
          .register(registry);
    }
    gauge(registry, "lexia.worker.hilos.ocupados", "worker", "ingesta", ingestaWorker, IngestaJobWorker::hilosOcupados);
    gauge(registry, "lexia.worker.hilos.ocupados", "worker", "coactiva", coactivaWorker, CoactivaJobWorker::hilosOcupados);
    gauge(registry, "lexia.memoria.borradores", null, null, carga, CargaDocumentoService::borradoresEnMemoria);
    gauge(registry, "lexia.memoria.borradores.bytes", null, null, carga, CargaDocumentoService::bytesBorradoresEnMemoria);
    gauge(registry, "lexia.memoria.sesiones.cache", null, null, sesiones, SessionCache::tamano);
    gauge(registry, "lexia.sse.oyentes", "flujo", "ingesta", ingesta, IngestaMasivaEscrituracionService::lotesConOyentes);
    gauge(registry, "lexia.sse.oyentes", "flujo", "coactivas", sseCoactivas, BatchEventPublisherService::expedientesConOyentes);
  }

  private static <T> void gauge(
      MeterRegistry registry,
      String nombre,
      String tag,
      String valor,
      ObjectProvider<T> proveedor,
      ToDoubleFunction<T> lectura) {
    Gauge.Builder<ObjectProvider<T>> builder =
        Gauge.builder(
            nombre,
            proveedor,
            p -> {
              T bean = p.getIfAvailable();
              return bean == null ? Double.NaN : lectura.applyAsDouble(bean);
            });
    if (tag != null) {
      builder.tag(tag, valor);
    }
    builder.register(registry);
  }

  /** Relee app.jobs si la foto venció. app.jobs no tiene RLS: son conteos de todos los tenants. */
  Foto vigente() {
    Foto actual = foto;
    Instant ahora = clock.instant();
    if (actual.tomada().plus(VIGENCIA).isAfter(ahora)) {
      return actual;
    }
    synchronized (this) {
      if (foto.tomada().plus(VIGENCIA).isAfter(ahora)) {
        return foto;
      }
      foto = leer(ahora);
      return foto;
    }
  }

  private Foto leer(Instant ahora) {
    JdbcTemplate template = jdbc.getIfAvailable();
    if (template == null) {
      return new Foto(ahora, Map.of(), Map.of());
    }
    Map<String, Double> cuentas = new HashMap<>();
    Map<String, Double> esperas = new HashMap<>();
    try {
      template.query(
          """
          SELECT tipo, estado, count(*)::bigint AS n,
                 COALESCE(EXTRACT(EPOCH FROM now()
                          - min(created_at) FILTER (WHERE estado = 'PENDIENTE')), 0) AS espera
            FROM app.jobs
           WHERE estado IN ('PENDIENTE', 'EN_PROCESO')
              OR (estado = 'ERROR' AND created_at > now() - INTERVAL '24 hours')
           GROUP BY tipo, estado
          """,
          rs -> {
            String tipo = rs.getString("tipo");
            String estado = rs.getString("estado");
            cuentas.put(tipo + "|" + estado, (double) rs.getLong("n"));
            if ("PENDIENTE".equals(estado)) {
              esperas.put(tipo, rs.getDouble("espera"));
            }
          });
    } catch (RuntimeException ex) {
      // Una métrica no debe tumbar el scrape, pero el fallo queda registrado y los valores en NaN.
      LOG.warn("No se pudo leer app.jobs para métricas: {}", ex.getMessage());
      return new Foto(ahora, null, null);
    }
    return new Foto(ahora, cuentas, esperas);
  }

  record Foto(Instant tomada, Map<String, Double> cuentas, Map<String, Double> esperas) {
    double cuenta(String tipo, String estado) {
      return cuentas == null ? Double.NaN : cuentas.getOrDefault(tipo + "|" + estado, 0d);
    }

    double espera(String tipo) {
      return esperas == null ? Double.NaN : esperas.getOrDefault(tipo, 0d);
    }
  }
}
