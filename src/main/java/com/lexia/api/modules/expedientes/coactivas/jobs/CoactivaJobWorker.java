package com.lexia.api.modules.expedientes.coactivas.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaValidacionIaWorker;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import com.lexia.api.modules.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclama solo jobs de coactivas. La ingesta de escrituración la toma
 * {@code escrituracion.masivo.IngestaJobWorker}.
 */
@Component("coactivaJobWorker")
public class CoactivaJobWorker {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaJobWorker.class);
  private static final String NODE = UUID.randomUUID().toString();
  static final Collection<String> TIPOS_COACTIVA =
      List.of("COACTIVA_DIAGNOSTICO", "COACTIVA_VALIDACION_ANEXO");

  private final CoactivaJobQueue cola;
  private final ObjectProvider<CoactivaExpedienteAnalisisWorker> coactivas;
  private final ObjectProvider<CoactivaValidacionIaWorker> anexos;
  private final BatchEventPublisherService sse;
  private final ObjectMapper json;
  private final ExecutorService anexoPool =
      Executors.newFixedThreadPool(4, r -> {
        Thread hilo = new Thread(r, "coactiva-anexo");
        hilo.setDaemon(true);
        return hilo;
      });
  private final ScheduledExecutorService latido =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "jobs-latido-coactiva");
        hilo.setDaemon(true);
        return hilo;
      });

  public CoactivaJobWorker(
      CoactivaJobQueue cola,
      ObjectProvider<CoactivaExpedienteAnalisisWorker> coactivas,
      ObjectProvider<CoactivaValidacionIaWorker> anexos,
      BatchEventPublisherService sse,
      ObjectMapper json) {
    this.cola = cola;
    this.coactivas = coactivas;
    this.anexos = anexos;
    this.sse = sse;
    this.json = json;
    latido.scheduleAtFixedRate(this::renovar, 30, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  void cerrar() {
    latido.shutdownNow();
    anexoPool.shutdownNow();
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    tomarCoactiva();
  }

  private void tomarCoactiva() {
    Optional<CoactivaJobQueue.Job> job = cola.reclamar(NODE, TIPOS_COACTIVA);
    if (job.isEmpty()) {
      return;
    }
    CoactivaJobQueue.Job actual = job.get();
    LOG.info("Tarea tomada app.jobs id={} tipo={}", actual.id(), actual.tipo());
    if ("COACTIVA_VALIDACION_ANEXO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrAnexo(actual));
    } else if ("COACTIVA_DIAGNOSTICO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrDiagnostico(actual));
    }
  }

  private void correrDiagnostico(CoactivaJobQueue.Job job) {
    RlsConnectionProvider.setTenantId(job.tenantId());
    try {
      CoactivaExpedienteAnalisisWorker worker = coactivas.getIfAvailable();
      if (worker == null) {
        throw new IllegalStateException("Diagnóstico coactivo no disponible");
      }
      worker.ejecutar(job.tenantId(), archivoId(job.payload()));
      cola.completar(job.id(), NODE);
    } catch (RuntimeException ex) {
      LOG.warn("job {} falló: {}", job.id(), ex.getMessage());
      cola.fallar(job.id(), NODE, ex.getMessage());
      abandonarCoactiva(job, ex);
    } finally {
      AuthContext.clear();
      TenantContext.clear();
    }
  }

  private void correrAnexo(CoactivaJobQueue.Job job) {
    RlsConnectionProvider.setTenantId(job.tenantId());
    UUID expedienteId = uuidEn(job.payload(), "expedienteId");
    try {
      CoactivaValidacionIaWorker worker = anexos.getIfAvailable();
      if (worker == null) {
        throw new IllegalStateException("Validación de anexo no disponible");
      }
      if (expedienteId != null) {
        sse.publicar(expedienteId, "ANALYSIS_STARTED", Map.of("expedienteId", expedienteId.toString()));
      }
      worker.ejecutar(job.tenantId(), archivoId(job.payload()));
      if (expedienteId != null) {
        sse.publicar(expedienteId, "ANALYSIS_COMPLETED", Map.of("expedienteId", expedienteId.toString()));
      }
      cola.completar(job.id(), NODE);
    } catch (RuntimeException ex) {
      LOG.warn("anexo {} falló: {}", job.id(), ex.getMessage());
      if (expedienteId != null) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        sse.publicar(expedienteId, "ANALYSIS_FAILED", Map.of("error", msg));
      }
      cola.fallar(job.id(), NODE, ex.getMessage());
      abandonarCoactiva(job, ex);
    } finally {
      TenantContext.clear();
    }
  }

  /** Mismo umbral que {@link CoactivaJobQueue#fallar}: al tercer intento el job queda en ERROR. */
  private void abandonarCoactiva(CoactivaJobQueue.Job job, RuntimeException ex) {
    CoactivaExpedienteAnalisisWorker worker = coactivas.getIfAvailable();
    if (job.intentos() < 3 || worker == null) {
      return;
    }
    try {
      worker.abandonar(job.tenantId(), archivoId(job.payload()), ex.getMessage());
    } catch (RuntimeException cierre) {
      LOG.warn("job {} no pudo cerrar el análisis: {}", job.id(), cierre.getMessage());
    }
  }

  /** {@code CAST(payload AS text)} devuelve el jsonb normalizado ({@code "k": "v"}): hay que parsearlo. */
  private UUID uuidEn(String payload, String campo) {
    try {
      return uuid(json.readTree(payload), campo);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
      return null;
    }
  }

  private static UUID uuid(JsonNode nodo, String campo) {
    String value = nodo.path(campo).asText("");
    if (value.isBlank()) {
      return null;
    }
    return UUID.fromString(value);
  }

  private void renovar() {
    try {
      cola.latido(NODE);
    } catch (RuntimeException ex) {
      LOG.warn("latido de jobs falló: {}", ex.getMessage());
    }
  }

  private UUID archivoId(String payload) {
    UUID id = uuidEn(payload, "archivoId");
    if (id == null) {
      throw new IllegalArgumentException("payload sin archivoId");
    }
    return id;
  }
}
