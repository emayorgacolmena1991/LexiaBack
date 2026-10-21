package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.escrituracion.masivo.JobRepository.JobRow;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  public IngestaJobWorker(
      JobRepository jobs, IngestaMasivaEscrituracionService ingesta, ObjectMapper objectMapper) {
    this.jobs = jobs;
    this.ingesta = ingesta;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 2000)
  public void poll() {
    jobs.liberarJobsColgados();
    Optional<JobRow> claimed = jobs.reclamar("PROCESAR_DOCUMENTO");
    if (claimed.isEmpty()) {
      return;
    }
    JobRow job = claimed.get();
    ProcesarDocumento payload;
    try {
      payload = objectMapper.readValue(job.payload(), ProcesarDocumento.class);
    } catch (Exception ex) {
      jobs.error(job.id(), "payload ilegible: " + ex.getMessage());
      return;
    }
    AuthContext.set(new AuthPrincipal(payload.userId(), null, payload.tenantId(), null));
    TenantContext.setTenantId(payload.tenantId());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                payload.userId().toString(),
                null,
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("USER"),
                    new SimpleGrantedAuthority("expedientes:caso:escribir"))));
    try {
      ingesta.procesarDocumento(payload);
      jobs.completar(job.id());
    } catch (RuntimeException ex) {
      if (job.intentos() >= 3) {
        jobs.error(job.id(), ex.getMessage());
      } else {
        jobs.devolverAPendiente(job.id());
      }
      LOG.error("job {} intento {} falló", job.id(), job.intentos(), ex);
      throw ex;
    } finally {
      SecurityContextHolder.clearContext();
      AuthContext.clear();
      TenantContext.clear();
    }
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
}
