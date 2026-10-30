package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.escrituracion.masivo.JobRepository.JobRow;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    jobs.liberarJobsColgados();
    Optional<JobRow> documento = jobs.reclamar("PROCESAR_DOCUMENTO");
    Optional<JobRow> ia = Optional.empty();
    if (documento.isEmpty()) {
      ia = jobs.reclamar("PROCESAR_IA_EXPEDIENTE");
    }
    LOG.info("ingesta poll documento={} ia={}", documento.isPresent(), ia.isPresent());
    if (documento.isPresent()) {
      JobRow job = documento.get();
      try {
        CompletableFuture.supplyAsync(
                () -> {
                  ejecutarDocumento(job);
                  return null;
                },
                executor)
            .get(120, TimeUnit.SECONDS);
        jobs.completar(job.id());
      } catch (TimeoutException ex) {
        LOG.error("worker timeout job={} tipo=DOCUMENTO", job.id());
        fallar(job, new RuntimeException("Timeout 120s"));
      } catch (ExecutionException ex) {
        if (ex.getCause() instanceof JobTerminalHandled) {
          return;
        }
        LOG.error("worker ERROR job={} tipo=DOCUMENTO", job.id(), ex.getCause());
        fallar(job, toRuntime(ex.getCause()));
      } catch (Exception ex) {
        LOG.error("worker ERROR job={} tipo=DOCUMENTO", job.id(), ex);
        fallar(job, toRuntime(ex));
      }
      return;
    }
    if (ia.isPresent()) {
      JobRow job = ia.get();
      try {
        CompletableFuture.supplyAsync(
                () -> {
                  ejecutarIa(job);
                  return null;
                },
                executor)
            .get(120, TimeUnit.SECONDS);
        jobs.completar(job.id());
      } catch (TimeoutException ex) {
        LOG.error("worker timeout job={} tipo=IA", job.id());
        fallar(job, new RuntimeException("Timeout 120s"));
      } catch (ExecutionException ex) {
        if (ex.getCause() instanceof JobTerminalHandled) {
          return;
        }
        LOG.error("worker ERROR job={} tipo=IA", job.id(), ex.getCause());
        fallar(job, toRuntime(ex.getCause()));
      } catch (Exception ex) {
        LOG.error("worker ERROR job={} tipo=IA", job.id(), ex);
        fallar(job, toRuntime(ex));
      }
    }
  }

  private void ejecutarDocumento(JobRow job) {
    ProcesarDocumento payload;
    try {
      payload = objectMapper.readValue(job.payload(), ProcesarDocumento.class);
    } catch (Exception ex) {
      jobs.error(job.id(), "payload ilegible: " + ex.getMessage());
      throw new JobTerminalHandled();
    }
    AuthContext.set(new AuthPrincipal(payload.userId(), null, payload.tenantId(), null));
    TenantContext.setTenantId(payload.tenantId());
    autenticar(payload.userId());
    try {
      ingesta.procesarDocumento(payload);
    } finally {
      limpiarContexto();
    }
  }

  private void ejecutarIa(JobRow job) {
    ProcesarIaExpediente payload;
    try {
      payload = objectMapper.readValue(job.payload(), ProcesarIaExpediente.class);
    } catch (Exception ex) {
      jobs.error(job.id(), "payload ilegible: " + ex.getMessage());
      throw new JobTerminalHandled();
    }
    AuthContext.set(new AuthPrincipal(payload.userId(), null, payload.tenantId(), null));
    TenantContext.setTenantId(payload.tenantId());
    autenticar(payload.userId());
    try {
      try {
        ingesta.procesarIaExpediente(payload);
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
    } else {
      jobs.devolverAPendiente(job.id());
    }
    LOG.error("job fallar job={} intentos={}", job.id(), job.intentos(), ex);
  }

  private static final class JobTerminalHandled extends RuntimeException {}

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
