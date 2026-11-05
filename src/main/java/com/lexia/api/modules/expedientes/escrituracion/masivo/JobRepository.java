package com.lexia.api.modules.expedientes.escrituracion.masivo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobRepository {

  private final JdbcTemplate jdbc;

  public JobRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID encolar(UUID tenantId, String tipo, String payloadJson, String workerId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO app.jobs (id, tenant_id, tipo, payload, estado, prioridad, idempotency_key, worker_id)
        VALUES (?, ?, ?, ?::jsonb, 'PENDIENTE', 0, NULL, ?)
        """,
        id,
        tenantId,
        tipo,
        payloadJson,
        workerId);
    return id;
  }

  /** Pasa una fila PENDIENTE a EN_PROCESO. El lock se suelta al cerrar la transacción. */
  @Transactional
  public Optional<JobRow> reclamar(String tipo, String workerId) {
    return jdbc
        .query(
            """
            UPDATE app.jobs
               SET estado = 'EN_PROCESO', intentos = intentos + 1
             WHERE id = (
               SELECT id
                 FROM app.jobs
                WHERE estado = 'PENDIENTE'
                  AND tipo = ?
                  AND worker_id = ?
                ORDER BY prioridad DESC, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
             )
            RETURNING id, tenant_id, payload::text, intentos
            """,
            (rs, row) ->
                new JobRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString(3),
                    rs.getInt("intentos")),
            tipo,
            workerId)
        .stream()
        .findFirst();
  }

  public int liberarJobsColgados(String workerId) {
    jdbc.update(
        """
        UPDATE app.ingesta_item
           SET detalle = jsonb_set(
                 jsonb_set(
                   jsonb_set(detalle, '{ocrEstado}', '"ERROR"'),
                   '{semaforo}', '"ROJO"'),
                 '{observaciones}', '"Job colgado >5min. Reintentar o reemplazar."'),
               error_log = 'Job colgado >5min',
               estado = 'ERROR'
         WHERE lote_id IN (
                 SELECT (payload->>'loteId')::uuid
                   FROM app.jobs
                  WHERE estado = 'EN_PROCESO'
                    AND worker_id = ?
                    AND tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
                    AND created_at < NOW() - INTERVAL '5 minutes')
           AND (detalle->>'ocrEstado') = 'PROCESANDO'
        """,
        workerId);
    return jdbc.update(
        """
        UPDATE app.jobs
           SET estado = 'PENDIENTE', intentos = intentos + 1
         WHERE estado = 'EN_PROCESO'
           AND worker_id = ?
           AND tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
           AND created_at < NOW() - INTERVAL '5 minutes'
        """,
        workerId);
  }

  public int pendientesDelExpediente(UUID expedienteId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT count(*)::int FROM app.jobs
            WHERE tipo = 'PROCESAR_DOCUMENTO'
              AND estado = 'PENDIENTE'
              AND payload->>'expedienteId' = ?
            """,
            Integer.class,
            expedienteId.toString());
    return n == null ? 0 : n;
  }

  public int enProcesoDelExpediente(UUID expedienteId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT count(*)::int FROM app.jobs
            WHERE tipo = 'PROCESAR_DOCUMENTO'
              AND estado = 'EN_PROCESO'
              AND payload->>'expedienteId' = ?
            """,
            Integer.class,
            expedienteId.toString());
    return n == null ? 0 : n;
  }

  /** app.jobs no tiene RLS: el tenant se filtra aquí. */
  public JobsEnCola enColaDelLote(UUID tenantId, UUID loteId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FILTER (WHERE tipo = 'PROCESAR_DOCUMENTO')::int,
               count(*) FILTER (WHERE tipo = 'PROCESAR_IA_EXPEDIENTE')::int
          FROM app.jobs
         WHERE estado IN ('PENDIENTE', 'EN_PROCESO')
           AND tenant_id = ?
           AND payload->>'loteId' = ?
        """,
        (rs, row) -> new JobsEnCola(rs.getInt(1), rs.getInt(2)),
        tenantId,
        loteId.toString());
  }

  public void devolverAPendiente(UUID id) {
    jdbc.update("UPDATE app.jobs SET estado = 'PENDIENTE' WHERE id = ?", id);
  }

  public void completar(UUID id) {
    jdbc.update("UPDATE app.jobs SET estado = 'COMPLETADO', error_log = NULL WHERE id = ?", id);
  }

  public void error(UUID id, String errorLog) {
    String log = errorLog == null ? "" : errorLog;
    if (log.length() > 4000) {
      log = log.substring(0, 4000);
    }
    jdbc.update("UPDATE app.jobs SET estado = 'ERROR', error_log = ? WHERE id = ?", log, id);
  }

  public record JobRow(UUID id, UUID tenantId, String payload, int intentos) {}

  public record JobsEnCola(int encolados, int iaEncolados) {}
}
