package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import com.lexia.api.modules.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  private final ObjectProvider<IaAnalysisService> escrituracion;
  private final BatchEventPublisherService sse;
  private final ObjectMapper json;
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
      ObjectProvider<IaAnalysisService> escrituracion,
      BatchEventPublisherService sse,
      ObjectMapper json) {
    this.cola = cola;
    this.ingesta = ingesta;
    this.coactivas = coactivas;
    this.escrituracion = escrituracion;
    this.sse = sse;
    this.json = json;
    latido.scheduleAtFixedRate(this::renovar, 30, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  void cerrar() {
    latido.shutdownNow();
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    Optional<IngestaJobQueue.Job> job = cola.reclamar(NODE);
    if (job.isEmpty()) {
      return;
    }
    IngestaJobQueue.Job actual = job.get();
    RlsConnectionProvider.setTenantId(actual.tenantId());
    try {
      if ("COACTIVA_DIAGNOSTICO".equals(actual.tipo())) {
        CoactivaExpedienteAnalisisWorker worker = coactivas.getIfAvailable();
        if (worker == null) {
          throw new IllegalStateException("Diagnóstico coactivo no disponible");
        }
        worker.ejecutar(actual.tenantId(), archivoId(actual.payload()));
      } else if ("ESCRITURACION_ANALISIS".equals(actual.tipo())) {
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
    sse.publicar(expedienteId, "ANALYSIS_STARTED", Map.of("jobId", job.id().toString()));
    try {
      var resultado =
          servicio.analizarExpedienteConPromptProducto(
              expedienteId,
              new AnalysisRequestDTO(
                  nodo.path("forceReanalysis").asBoolean(false), nodo.path("sessionIdOcr").asText("")));
      sse.publicar(expedienteId, "ANALYSIS_COMPLETED", resultado);
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
