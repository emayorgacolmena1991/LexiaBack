package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaValidacionIaWorker;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import com.lexia.api.modules.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
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

/** Saca trabajos de {@code app.jobs}. El latido renueva el candado mientras corre OCR o el LLM. */
@Component
public class IngestaJobWorker {

  private static final Logger LOG = LoggerFactory.getLogger(IngestaJobWorker.class);
  private static final String NODE = UUID.randomUUID().toString();

  private final IngestaJobQueue cola;
  private final IngestaMasivaEscrituracionService ingesta;
  private final ObjectProvider<CoactivaExpedienteAnalisisWorker> coactivas;
  private final ObjectProvider<CoactivaValidacionIaWorker> anexos;
  private final ObjectProvider<IaAnalysisService> escrituracion;
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
        Thread hilo = new Thread(r, "jobs-latido");
        hilo.setDaemon(true);
        return hilo;
      });

  public IngestaJobWorker(
      IngestaJobQueue cola,
      IngestaMasivaEscrituracionService ingesta,
      ObjectProvider<CoactivaExpedienteAnalisisWorker> coactivas,
      ObjectProvider<CoactivaValidacionIaWorker> anexos,
      ObjectProvider<IaAnalysisService> escrituracion,
      BatchEventPublisherService sse,
      ObjectMapper json) {
    this.cola = cola;
    this.ingesta = ingesta;
    this.coactivas = coactivas;
    this.anexos = anexos;
    this.escrituracion = escrituracion;
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
    Optional<IngestaJobQueue.Job> job = cola.reclamar(NODE);
    if (job.isEmpty()) {
      return;
    }
    IngestaJobQueue.Job actual = job.get();
    LOG.info("Tarea tomada app.jobs id={} tipo={}", actual.id(), actual.tipo());
    if ("COACTIVA_VALIDACION_ANEXO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrAnexo(actual));
      return;
    }
    if ("COACTIVA_DIAGNOSTICO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrDiagnostico(actual));
      return;
    }
    RlsConnectionProvider.setTenantId(actual.tenantId());
    try {
      if ("ESCRITURACION_ANALISIS".equals(actual.tipo())) {
        correrEscrituracion(actual);
      } else {
        ingesta.ejecutarJob(actual);
      }
      cola.completar(actual.id(), NODE);
    } catch (RuntimeException ex) {
      LOG.warn("job {} falló: {}", actual.id(), ex.getMessage());
      cola.fallar(actual.id(), NODE, ex.getMessage());
    } finally {
      AuthContext.clear();
      TenantContext.clear();
    }
  }

  private void correrDiagnostico(IngestaJobQueue.Job job) {
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
    } finally {
      AuthContext.clear();
      TenantContext.clear();
    }
  }

  private void correrAnexo(IngestaJobQueue.Job job) {
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
    } finally {
      TenantContext.clear();
    }
  }

  private static UUID uuidEn(String payload, String campo) {
    String key = "\"" + campo + "\":\"";
    int i = payload == null ? -1 : payload.indexOf(key);
    if (i < 0) {
      return null;
    }
    int start = i + key.length();
    int end = payload.indexOf('"', start);
    String value = payload.substring(start, end);
    if (value.isBlank() || "null".equals(value)) {
      return null;
    }
    return UUID.fromString(value);
  }

  private void correrEscrituracion(IngestaJobQueue.Job job) {
    JsonNode nodo;
    try {
      nodo = json.readTree(job.payload());
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("payload de análisis ilegible");
    }
    UUID expedienteId = UUID.fromString(nodo.path("expedienteId").asText());
    AuthContext.set(
        new AuthPrincipal(
            uuid(nodo, "userId"), uuid(nodo, "sessionId"), job.tenantId(), uuid(nodo, "membershipId")));
    IaAnalysisService servicio = escrituracion.getIfAvailable();
    if (servicio == null) {
      throw new IllegalStateException("Análisis de escrituración no disponible");
    }
    LOG.info(
        "[WORKER] Tarea tomada app.jobs id={} tipo=ESCRITURACION_ANALISIS. Ejecutando LLM...",
        job.id());
    sse.publicar(expedienteId, "ANALYSIS_STARTED", Map.of("jobId", job.id().toString()));
    try {
      boolean force = nodo.path("force").asBoolean(false) || nodo.path("forceReanalysis").asBoolean(false);
      var resultado =
          servicio.analizarExpedienteConPromptProducto(
              expedienteId,
              new AnalysisRequestDTO(force, nodo.path("sessionIdOcr").asText("")));
      sse.publicar(expedienteId, "ANALYSIS_COMPLETED", resultado);
      LOG.info(
          "[WORKER OK] Dictamen guardado en app.extracted_data y app.title_study. SSE emitido.");
    } catch (RuntimeException ex) {
      String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      sse.publicar(expedienteId, "ANALYSIS_FAILED", Map.of("error", msg));
      throw ex;
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

  private static UUID archivoId(String payload) {
    String key = "\"archivoId\":\"";
    int i = payload == null ? -1 : payload.indexOf(key);
    if (i < 0) {
      throw new IllegalArgumentException("payload sin archivoId");
    }
    int start = i + key.length();
    int end = payload.indexOf('"', start);
    return UUID.fromString(payload.substring(start, end));
  }
}
