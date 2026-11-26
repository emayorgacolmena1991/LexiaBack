package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.notifications.InAppNotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Alertas cuando jobs de ingesta quedan atascados o agotan reintentos. */
@Service
public class LexiaJobsAlertasService {

  private static final Logger LOG = LoggerFactory.getLogger(LexiaJobsAlertasService.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ObjectProvider<InAppNotificationService> notificaciones;
  private final int stuckMinutos;

  public LexiaJobsAlertasService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      ObjectProvider<InAppNotificationService> notificaciones,
      @Value("${lexia.jobs.stuck-minutes:45}") int stuckMinutos) {
    this.jdbc = jdbc;
    this.json = json;
    this.notificaciones = notificaciones;
    this.stuckMinutos = Math.max(15, stuckMinutos);
  }

  public void notificarJobsAgotados(List<JobRepository.JobRow> agotados) {
    for (JobRepository.JobRow job : agotados) {
      notificarJobStuck(job.tenantId(), job.id(), job.payload(), "reintentos agotados");
    }
  }

  @Scheduled(fixedDelayString = "${lexia.jobs.stuck-check-ms:300000}")
  public void revisarJobsAtascados() {
    List<JobRowAlerta> rows =
        jdbc.query(
            """
            SELECT id, tenant_id, tipo, payload::text
              FROM app.jobs
             WHERE estado = 'EN_PROCESO'
               AND tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
               AND locked_until IS NOT NULL
               AND locked_until < NOW() - ? * INTERVAL '1 minute'
             LIMIT 20
            """,
            (rs, row) ->
                new JobRowAlerta(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("tipo"),
                    rs.getString("payload")),
            stuckMinutos);
    for (JobRowAlerta row : rows) {
      notificarJobStuck(row.tenantId(), row.id(), row.payload(), "tiempo de bloqueo vencido");
    }
  }

  private void notificarJobStuck(UUID tenantId, UUID jobId, String payload, String motivo) {
    String loteId = extraerLoteId(payload);
    String dedupe = "job-stuck:" + jobId;
    notificaciones.ifAvailable(
        svc ->
            svc.notifyTenantAdmins(
                tenantId,
                null,
                "JOB_STUCK",
                Map.of(
                    "jobId", jobId.toString(),
                    "loteId", loteId == null ? "" : loteId,
                    "motivo", motivo),
                loteId == null
                    ? "/escrituracion/ingesta/lotes"
                    : "/escrituracion/ingesta/lotes/" + loteId,
                dedupe));
    UUID actor = extraerUserId(payload);
    if (actor != null && loteId != null) {
      notificaciones.ifAvailable(
          svc ->
              svc.notifyOperaciones(
                  tenantId,
                  actor,
                  "JOB_STUCK",
                  Map.of(
                      "jobId", jobId.toString(),
                      "loteId", loteId,
                      "motivo", motivo),
                  "/escrituracion/ingesta/lotes/" + loteId,
                  dedupe + ":op"));
    }
    LOG.warn("job atascado notificado job={} lote={} motivo={}", jobId, loteId, motivo);
  }

  private String extraerLoteId(String payload) {
    try {
      JsonNode n = json.readTree(payload == null ? "{}" : payload);
      JsonNode lote = n.get("loteId");
      return lote == null || lote.isNull() ? null : lote.asText();
    } catch (Exception ex) {
      return null;
    }
  }

  private UUID extraerUserId(String payload) {
    try {
      JsonNode n = json.readTree(payload == null ? "{}" : payload);
      JsonNode user = n.get("userId");
      if (user == null || user.isNull()) {
        return null;
      }
      return UUID.fromString(user.asText());
    } catch (Exception ex) {
      return null;
    }
  }

  private record JobRowAlerta(UUID id, UUID tenantId, String tipo, String payload) {}
}