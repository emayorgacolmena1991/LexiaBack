package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaValidacionIaWorker;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import com.lexia.api.modules.expedientes.minutas.CapturaBiessService;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.tenancy.RlsConnectionProvider;
import com.lexia.api.modules.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  static final Collection<String> TIPOS_ESCRITURACION =
      List.of(
          "IA_ANALISIS",
          "OCR_EXTRACTION",
          "OCR_CONSOLIDAR",
          "IA_ANALISIS_PROFUNDO",
          "BIESS_CAPTURA",
          "MINUTA_RENDER",
          "MINUTA_ENRIQUECER",
          "UPLOAD_PROCESAR");
  static final Collection<String> TIPOS_COACTIVA =
      List.of("COACTIVA_DIAGNOSTICO", "COACTIVA_VALIDACION_ANEXO");

  private final IngestaJobQueue cola;
  private final IngestaMasivaEscrituracionService ingesta;
  private final ObjectProvider<CoactivaExpedienteAnalisisWorker> coactivas;
  private final ObjectProvider<CoactivaValidacionIaWorker> anexos;
  private final ObjectProvider<IaAnalysisService> escrituracion;
  private final ObjectProvider<MinutaGenerationService> minutas;
  private final ObjectProvider<CapturaBiessService> capturas;
  private final BatchEventPublisherService sse;
  private final ObjectMapper json;
  private final ExecutorService anexoPool =
      Executors.newFixedThreadPool(4, r -> {
        Thread hilo = new Thread(r, "coactiva-anexo");
        hilo.setDaemon(true);
        return hilo;
      });
  /** Un análisis de escrituración a la vez. El tick no espera al LLM, así Coactivas sigue reclamando. */
  private final ExecutorService escrituracionPool =
      Executors.newSingleThreadExecutor(r -> {
        Thread hilo = new Thread(r, "escrituracion-job");
        hilo.setDaemon(true);
        return hilo;
      });
  private final AtomicBoolean escrituracionEnCurso = new AtomicBoolean();
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
      ObjectProvider<MinutaGenerationService> minutas,
      ObjectProvider<CapturaBiessService> capturas,
      BatchEventPublisherService sse,
      ObjectMapper json) {
    this.cola = cola;
    this.ingesta = ingesta;
    this.coactivas = coactivas;
    this.anexos = anexos;
    this.escrituracion = escrituracion;
    this.minutas = minutas;
    this.capturas = capturas;
    this.sse = sse;
    this.json = json;
    latido.scheduleAtFixedRate(this::renovar, 30, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  void cerrar() {
    latido.shutdownNow();
    anexoPool.shutdownNow();
    escrituracionPool.shutdownNow();
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    tomarCoactiva();
    if (!escrituracionEnCurso.compareAndSet(false, true)) {
      return;
    }
    escrituracionPool.execute(() -> {
      try {
        tomarEscrituracion();
      } finally {
        escrituracionEnCurso.set(false);
      }
    });
  }

  private void tomarCoactiva() {
    Optional<IngestaJobQueue.Job> job = cola.reclamar(NODE, TIPOS_COACTIVA);
    if (job.isEmpty()) {
      return;
    }
    IngestaJobQueue.Job actual = job.get();
    LOG.info("Tarea tomada app.jobs id={} tipo={}", actual.id(), actual.tipo());
    if ("COACTIVA_VALIDACION_ANEXO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrAnexo(actual));
    } else if ("COACTIVA_DIAGNOSTICO".equals(actual.tipo())) {
      anexoPool.execute(() -> correrDiagnostico(actual));
    }
  }

  private void tomarEscrituracion() {
    Optional<IngestaJobQueue.Job> job = cola.reclamar(NODE, TIPOS_ESCRITURACION);
    if (job.isEmpty()) {
      return;
    }
    IngestaJobQueue.Job actual = job.get();
    LOG.info("Tarea tomada app.jobs id={} tipo={}", actual.id(), actual.tipo());
    RlsConnectionProvider.setTenantId(actual.tenantId());
    LOG.info("worker tenant aplicado job={} tenant={}", actual.id(), actual.tenantId());
    try {
      if ("IA_ANALISIS".equals(actual.tipo())) {
        correrEscrituracion(actual);
      } else if ("BIESS_CAPTURA".equals(actual.tipo())) {
        correrCapturaBiess(actual);
      } else if ("MINUTA_RENDER".equals(actual.tipo()) || "MINUTA_ENRIQUECER".equals(actual.tipo())) {
        correrMinuta(actual);
      } else if ("IA_ANALISIS_PROFUNDO".equals(actual.tipo())) {
        correrProfundo(actual);
      } else {
        ingesta.ejecutarJob(actual);
      }
      cola.completar(actual.id(), NODE);
    } catch (RuntimeException ex) {
      if (sinOcrRecuperable(actual.tipo(), ex.getMessage())) {
        LOG.info(
            "worker-reprogramado job={} motivo=SIN_TEXTO_OCR intento={}/10",
            actual.id(),
            actual.intentos());
        cola.reprogramar(actual.id(), NODE, "SIN_TEXTO_OCR");
        if (agotado(actual.intentos())) {
          UUID expediente = uuidEn(actual.payload(), "expedienteId");
          if (expediente != null) {
            sse.publicar(
                expediente,
                "ANALYSIS_FAILED",
                Map.of("motivo", "SIN_TEXTO_OCR", "error", ex.getMessage()));
          }
        }
      } else {
        LOG.warn("job {} falló: {}", actual.id(), ex.getMessage());
        cola.fallar(actual.id(), NODE, ex.getMessage());
      }
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
      abandonarCoactiva(job, ex);
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
      abandonarCoactiva(job, ex);
    } finally {
      TenantContext.clear();
    }
  }

  /** Mismo umbral que {@link IngestaJobQueue#fallar}: al tercer intento el job queda en ERROR. */
  private void abandonarCoactiva(IngestaJobQueue.Job job, RuntimeException ex) {
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

  static boolean sinOcrRecuperable(String tipo, String mensaje) {
    return "IA_ANALISIS".equals(tipo) && mensaje != null && mensaje.contains("Sin texto OCR");
  }

  static boolean agotado(int intento) {
    return intento >= 10;
  }

  /** {@code CAST(payload AS text)} devuelve el jsonb normalizado ({@code "k": "v"}): hay que parsearlo. */
  private UUID uuidEn(String payload, String campo) {
    try {
      return uuid(json.readTree(payload), campo);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
      return null;
    }
  }

  /** Payload con la identidad del usuario que encoló: restaura {@link AuthContext} para el job. */
  private JsonNode comoUsuario(IngestaJobQueue.Job job) {
    JsonNode nodo;
    try {
      nodo = json.readTree(job.payload());
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("payload de " + job.tipo() + " ilegible");
    }
    AuthContext.set(
        new AuthPrincipal(
            uuid(nodo, "userId"), uuid(nodo, "sessionId"), job.tenantId(), uuid(nodo, "membershipId")));
    return nodo;
  }

  /** Error de negocio (imagen ilegible, sin datos): no se reintenta, se avisa por SSE y se cierra. */
  private void correrCapturaBiess(IngestaJobQueue.Job job) {
    JsonNode nodo = comoUsuario(job);
    UUID expedienteId = UUID.fromString(nodo.path("expedienteId").asText());
    CapturaBiessService servicio = capturas.getIfAvailable();
    if (servicio == null) {
      throw new IllegalStateException("Captura BIESS no disponible");
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(nodo.path("archivo").asText(""));
      var campos = servicio.extraer(expedienteId, bytes, nodo.path("mime").asText(""));
      sse.publicar(
          expedienteId,
          "BIESS_DONE",
          Map.of("expedienteId", expedienteId.toString(), "jobId", job.id().toString(), "campos", campos));
    } catch (ApiException ex) {
      sse.publicar(
          expedienteId,
          "BIESS_FAILED",
          Map.of("expedienteId", expedienteId.toString(), "error", String.valueOf(ex.getMessage())));
    } catch (RuntimeException ex) {
      if (job.intentos() >= 3) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        sse.publicar(expedienteId, "BIESS_FAILED", Map.of("expedienteId", expedienteId.toString(), "error", msg));
      }
      throw ex;
    }
  }

  private void correrMinuta(IngestaJobQueue.Job job) {
    JsonNode nodo = comoUsuario(job);
    MinutaGenerationService servicio = minutas.getIfAvailable();
    if (servicio == null) {
      throw new IllegalStateException("Minutas no disponible");
    }
    UUID minutaId = UUID.fromString(nodo.path("minutaId").asText());
    String session = nodo.path("sessionIdOcr").asText("");
    if ("MINUTA_ENRIQUECER".equals(job.tipo())) {
      UUID expedienteId = uuid(nodo, "expedienteId");
      if (expedienteId != null) {
        sse.publicar(expedienteId, "ENRIQUECER_STARTED", Map.of("minutaId", minutaId.toString()));
      }
      try {
        var minuta = servicio.enriquecerConIa(minutaId, session);
        sse.publicar(
            minuta.caseId(),
            "ENRIQUECER_COMPLETED",
            Map.of("minutaId", minuta.minutaId().toString(), "variables_pendientes", minuta.pendientes()));
      } catch (RuntimeException ex) {
        if (expedienteId != null) {
          sse.publicar(
              expedienteId,
              "ENRIQUECER_FAILED",
              Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        throw ex;
      }
      return;
    }
    var minuta = servicio.renderSinLlm(minutaId);
    sse.publicar(
        minuta.caseId(),
        "MINUTA_READY",
        Map.of("minutaId", minuta.minutaId().toString(), "variables_pendientes", minuta.pendientes()));
  }

  private void correrProfundo(IngestaJobQueue.Job job) {
    JsonNode nodo = comoUsuario(job);
    UUID expedienteId = UUID.fromString(nodo.path("expedienteId").asText());
    IaAnalysisService servicio = escrituracion.getIfAvailable();
    if (servicio == null) {
      throw new IllegalStateException("Análisis de escrituración no disponible");
    }
    sse.publicar(expedienteId, "ANALYSIS_STARTED", Map.of("jobId", job.id().toString(), "etapa", "profunda"));
    try {
      var resultado =
          servicio.analizarProfundo(
              expedienteId, new AnalysisRequestDTO(true, nodo.path("sessionIdOcr").asText("")));
      sse.publicar(expedienteId, "ANALYSIS_COMPLETED", resultado);
    } catch (RuntimeException ex) {
      String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      sse.publicar(expedienteId, "ANALYSIS_FAILED", Map.of("error", msg));
      throw ex;
    } finally {
      IaAnalysisService.limpiarProfundo();
    }
  }

  private void correrEscrituracion(IngestaJobQueue.Job job) {
    JsonNode nodo = comoUsuario(job);
    UUID expedienteId = UUID.fromString(nodo.path("expedienteId").asText());
    IaAnalysisService servicio = escrituracion.getIfAvailable();
    if (servicio == null) {
      throw new IllegalStateException("Análisis de escrituración no disponible");
    }
    LOG.info(
        "[WORKER] Tarea tomada app.jobs id={} tipo=IA_ANALISIS. Ejecutando LLM...",
        job.id());
    sse.publicar(expedienteId, "ANALYSIS_STARTED", Map.of("jobId", job.id().toString()));
    try {
      boolean force = nodo.path("force").asBoolean(false) || nodo.path("forceReanalysis").asBoolean(false);
      var resultado =
          servicio.analizarExpedienteConPromptProducto(
              expedienteId,
              new AnalysisRequestDTO(force, nodo.path("sessionIdOcr").asText("")));
      Map<String, Object> data = new java.util.LinkedHashMap<>();
      data.putAll(json.convertValue(resultado, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
      if (IaAnalysisService.profundoPendiente()) {
        data.put("profundoPendiente", true);
      }
      sse.publicar(expedienteId, "ANALYSIS_COMPLETED", data);
      String batch = nodo.path("batchId").asText("");
      if (!batch.isBlank()) {
        ingesta.marcarAnalisis(UUID.fromString(batch), nodo.path("clientRowId").asText(""), true, "OK");
        sse.publicar(UUID.fromString(batch), "ANALYSIS_COMPLETED", Map.of("expedienteId", expedienteId.toString()));
      }
      LOG.info(
          "[WORKER OK] Dictamen guardado en app.extracted_data y app.title_study. SSE emitido.");
    } catch (RuntimeException ex) {
      if (sinOcrRecuperable("IA_ANALISIS", ex.getMessage())) {
        throw ex;
      }
      String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      sse.publicar(expedienteId, "ANALYSIS_FAILED", Map.of("error", msg));
      throw ex;
    } finally {
      IaAnalysisService.limpiarProfundo();
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
