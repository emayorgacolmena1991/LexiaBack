package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.escrituracion.masivo.JobRepository.JobRow;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IngestaJobWorker {

  private static final Logger LOG = LoggerFactory.getLogger(IngestaJobWorker.class);

  private final JobRepository jobs;
  private final IngestaMasivaEscrituracionService ingesta;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;

  /** Solo se reclama con hilo libre: un job en cola gastaría sus 120 s sin haber empezado. */
  private final Semaphore capacidad = new Semaphore(IngestaWorkerExecutorConfig.HILOS);

  public IngestaJobWorker(
      JobRepository jobs,
      IngestaMasivaEscrituracionService ingesta,
      ObjectMapper objectMapper,
      ExecutorService ingestaWorkerExecutor) {
    this.jobs = jobs;
    this.ingesta = ingesta;
    this.objectMapper = objectMapper;
    this.executor = ingestaWorkerExecutor;
  }

  @Scheduled(fixedDelay = 2000)
  public void poll() {
    LOG.info("ingesta poll tick");
    jobs.liberarJobsColgados(ingesta.workerId());
    if (!capacidad.tryAcquire()) {
      return;
    }
    boolean lanzado = false;
    try {
      lanzado = reclamarYLanzar();
    } finally {
      if (!lanzado) {
        capacidad.release();
      }
    }
  }

  private boolean reclamarYLanzar() {
    Optional<JobRow> documento = jobs.reclamar("PROCESAR_DOCUMENTO", ingesta.workerId());
    Optional<JobRow> ia = Optional.empty();
    if (documento.isEmpty()) {
      ia = jobs.reclamar("PROCESAR_IA_EXPEDIENTE", ingesta.workerId());
    }
    LOG.info("ingesta poll documento={} ia={}", documento.isPresent(), ia.isPresent());
    if (documento.isPresent()) {
      JobRow job = documento.get();
      ejecutarConTimeout(job, "DOCUMENTO", cancelado -> ejecutarDocumento(job, cancelado));
      return true;
    }
    if (ia.isPresent()) {
      JobRow job = ia.get();
      ejecutarConTimeout(job, "IA", cancelado -> ejecutarIa(job, cancelado));
      return true;
    }
    return false;
  }

  /**
   * orTimeout no interrumpe la tarea: va dentro de un FutureTask, cuyo cancel(true) sí lo hace. La
   * bandera cubre al código que ignora la interrupción. El cierre va al pool porque el timeout
   * dispara en el hilo único del JDK, que no debe bloquearse con BD.
   */
  private void ejecutarConTimeout(JobRow job, String tipo, Consumer<BooleanSupplier> tarea) {
    AtomicBoolean cancelado = new AtomicBoolean();
    FutureTask<Void> ejecucion = new FutureTask<>(() -> tarea.accept(cancelado::get), null);
    CompletableFuture.runAsync(ejecucion, executor)
        .orTimeout(120, TimeUnit.SECONDS)
        .exceptionally(
            ex -> {
              cancelado.set(true);
              ejecucion.cancel(true);
              return null;
            })
        .thenRunAsync(() -> cerrarJob(job, tipo, ejecucion), executor);
  }

  private void cerrarJob(JobRow job, String tipo, FutureTask<Void> ejecucion) {
    try {
      ejecucion.get();
      jobs.completar(job.id());
    } catch (CancellationException ex) {
      LOG.error("worker timeout job={} tipo={}", job.id(), tipo);
      fallar(job, new RuntimeException("Timeout 120s"));
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof JobTerminalHandled terminal) {
        marcarItemComoError(job, terminal.loteId, terminal.itemId, "Documento ilegible");
        return;
      }
      LOG.error("worker ERROR job={} tipo={}", job.id(), tipo, ex.getCause());
      fallar(job, toRuntime(ex.getCause()));
    } catch (Exception ex) {
      LOG.error("worker ERROR job={} tipo={}", job.id(), tipo, ex);
      fallar(job, toRuntime(ex));
    } finally {
      capacidad.release();
    }
  }

  private void ejecutarDocumento(JobRow job, BooleanSupplier cancelado) {
    ProcesarDocumento payload;
    try {
      payload = objectMapper.readValue(job.payload(), ProcesarDocumento.class);
    } catch (Exception ex) {
      jobs.error(job.id(), "payload ilegible: " + ex.getMessage());
      throw terminalConItem(job);
    }
    AuthContext.set(new AuthPrincipal(payload.userId(), null, payload.tenantId(), null));
    TenantContext.setTenantId(payload.tenantId());
    autenticar(payload.userId());
    try {
      ingesta.procesarDocumento(payload, cancelado);
    } finally {
      limpiarContexto();
    }
  }

  private void ejecutarIa(JobRow job, BooleanSupplier cancelado) {
    ProcesarIaExpediente payload;
    try {
      payload = objectMapper.readValue(job.payload(), ProcesarIaExpediente.class);
    } catch (Exception ex) {
      jobs.error(job.id(), "payload ilegible: " + ex.getMessage());
      throw terminalConItem(job);
    }
    AuthContext.set(new AuthPrincipal(payload.userId(), null, payload.tenantId(), null));
    TenantContext.setTenantId(payload.tenantId());
    autenticar(payload.userId());
    try {
      try {
        ingesta.procesarIaExpediente(payload, cancelado);
      } catch (IllegalStateException ex) {
        if ("Expediente bloqueado".equals(ex.getMessage())) {
          jobs.error(job.id(), "Expediente bloqueado");
          throw new JobTerminalHandled();
        }
        throw ex;
      }
    } finally {
      TenantContext.clear();
      limpiarContexto();
    }
  }

  private static RuntimeException toRuntime(Throwable ex) {
    if (ex instanceof RuntimeException runtime) {
      return runtime;
    }
    return new RuntimeException(ex);
  }

  private void fallar(JobRow job, RuntimeException ex) {
    if (job.intentos() >= 3) {
      jobs.error(job.id(), ex.getMessage());
      JobTerminalHandled item = terminalConItem(job);
      marcarItemComoError(job, item.loteId, item.itemId, "Fallo en worker: " + ex.getMessage());
    } else {
      jobs.devolverAPendiente(job.id());
    }
    LOG.error("job fallar job={} intentos={}", job.id(), job.intentos(), ex);
  }

  /** app.jobs guarda JSONB: aunque el record no parsee, los ids suelen poder leerse del árbol. */
  private JobTerminalHandled terminalConItem(JobRow job) {
    try {
      JsonNode payload = objectMapper.readTree(job.payload());
      return new JobTerminalHandled(
          UUID.fromString(payload.path("loteId").asText()), payload.path("itemId").asText());
    } catch (Exception ex) {
      LOG.error("payload sin loteId/itemId job={}", job.id(), ex);
      return new JobTerminalHandled();
    }
  }

  /**
   * Solo en fallo terminal: un item en ERROR bloquea el reintento del job. Corre en el hilo del
   * poll, sin contexto; el tenant sale de la fila del job porque el payload puede estar roto.
   */
  private void marcarItemComoError(JobRow job, UUID loteId, String itemId, String motivo) {
    if (loteId == null) {
      return;
    }
    AuthContext.set(new AuthPrincipal(null, null, job.tenantId(), null));
    TenantContext.setTenantId(job.tenantId());
    try {
      ingesta.marcarItemComoError(loteId, itemId, motivo);
    } catch (RuntimeException ex) {
      LOG.error("no se pudo marcar item en ERROR job={}", job.id(), ex);
    } finally {
      limpiarContexto();
    }
  }

  private static final class JobTerminalHandled extends RuntimeException {
    private final UUID loteId;
    private final String itemId;

    private JobTerminalHandled() {
      this(null, null);
    }

    private JobTerminalHandled(UUID loteId, String itemId) {
      this.loteId = loteId;
      this.itemId = itemId;
    }
  }

  private static void autenticar(UUID userId) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("USER"),
                    new SimpleGrantedAuthority("expedientes:caso:escribir"))));
  }

  private static void limpiarContexto() {
    SecurityContextHolder.clearContext();
    AuthContext.clear();
    TenantContext.clear();
  }

  public record ProcesarDocumento(
      UUID loteId,
      String itemId,
      UUID expedienteId,
      String docId,
      String rutaArchivo,
      UUID tenantId,
      UUID userId,
      String tipoDocumento) {}

  public record ProcesarIaExpediente(
      UUID loteId,
      String itemId,
      UUID expedienteId,
      UUID tenantId,
      UUID userId,
      String sessionId) {}
}
