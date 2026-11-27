package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.lexia.api.modules.notifications.InAppNotificationService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Avisos al cerrar el cotejo de un lote de ingesta masiva (operador + administracion). */
@Service
public class IngestaLoteNotificacionService {

  private static final Logger LOG = LoggerFactory.getLogger(IngestaLoteNotificacionService.class);

  private final JdbcTemplate jdbc;
  private final JobRepository jobs;
  private final ObjectProvider<InAppNotificationService> notificaciones;

  public IngestaLoteNotificacionService(
      JdbcTemplate jdbc,
      JobRepository jobs,
      ObjectProvider<InAppNotificationService> notificaciones) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.notificaciones = notificaciones;
  }

  public void revisarCierreLote(UUID tenantId, UUID loteId, UUID actorUserId) {
    if (tenantId == null || loteId == null) {
      return;
    }
    JobRepository.JobsEnCola cola = jobs.enColaDelLote(tenantId, loteId);
    if (cola.encolados() > 0 || cola.iaEncolados() > 0) {
      return;
    }
    Integer[] counts =
        jdbc.queryForObject(
            """
            SELECT count(*)::int,
                   count(*) FILTER (WHERE estado = 'COMPLETADO')::int,
                   count(*) FILTER (WHERE estado = 'ERROR')::int,
                   count(*) FILTER (WHERE estado IN ('PENDIENTE','EN_PROCESO'))::int
              FROM app.ingesta_item
             WHERE lote_id = ? AND tenant_id = ?
            """,
            (rs, row) ->
                new Integer[] {
                  rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)
                },
            loteId,
            tenantId);
    if (counts == null || counts[3] > 0) {
      return;
    }
    int total = counts[0];
    int ok = counts[1];
    int err = counts[2];
    String href = "/escrituracion/ingesta/lotes/" + loteId;
    String dedupe = "ingesta-lote-done:" + loteId;
    Map<String, String> params =
        Map.of(
            "total", String.valueOf(total),
            "ok", String.valueOf(ok),
            "error", String.valueOf(err),
            "loteId", loteId.toString());
    notificaciones.ifAvailable(
        svc -> {
          if (actorUserId != null) {
            svc.notifyOperaciones(
                tenantId, actorUserId, "INGESTA_LOTE_FINALIZADO", params, href, dedupe + ":op:" + actorUserId);
          }
          svc.notifyTenantAdmins(
              tenantId,
              actorUserId,
              "INGESTA_LOTE_FINALIZADO",
              params,
              href,
              dedupe + ":adm");
        });
    LOG.info(
        "ingesta lote cerrado notificado lote={} total={} ok={} error={}", loteId, total, ok, err);
  }
}