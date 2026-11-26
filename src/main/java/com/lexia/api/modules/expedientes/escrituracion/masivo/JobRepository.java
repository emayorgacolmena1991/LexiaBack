package com.lexia.api.modules.expedientes.escrituracion.masivo;

import java.util.List;
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

  /**
   * @return {@code true} si se creó un job nuevo; {@code false} si ya había uno vivo con la misma clave.
   */
  public boolean encolarIaExpediente(
      UUID tenantId, String payloadJson, String workerId, String idempotencyKey) {
    Boolean vivo =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM app.jobs
               WHERE tenant_id = ?
                 AND tipo = 'PROCESAR_IA_EXPEDIENTE'
                 AND idempotency_key = ?
                 AND estado IN ('PENDIENTE', 'EN_PROCESO')
            )
            """,
            Boolean.class,
            tenantId,
            idempotencyKey);
    if (Boolean.TRUE.equals(vivo)) {
      return false;
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO app.jobs (id, tenant_id, tipo, payload, estado, prioridad, idempotency_key, worker_id)
        VALUES (?, ?, 'PROCESAR_IA_EXPEDIENTE', ?::jsonb, 'PENDIENTE', 0, ?, ?)
        """,
        id,
        tenantId,
        payloadJson,
        idempotencyKey,
        workerId);
    return true;
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

  /**
   * Pasa una fila PENDIENTE vencida ({@code run_at}) a EN_PROCESO. {@code lockSegundos} debe superar
   * el timeout del job: si no, {@link #liberarJobsColgados} reencolaría un job vivo.
   */
  @Transactional
  public Optional<JobRow> reclamar(String tipo, String workerId, long lockSegundos) {
    return jdbc
        .query(
            """
            UPDATE app.jobs
               SET estado = 'EN_PROCESO',
                   intentos = intentos + 1,
                   locked_until = NOW() + ? * INTERVAL '1 second'
             WHERE id = (
               SELECT id
                 FROM app.jobs
                WHERE estado = 'PENDIENTE'
                  AND tipo = ?
                  AND worker_id = ?
                  AND run_at <= NOW()
                ORDER BY prioridad DESC, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
             )
            RETURNING id, tenant_id, payload::text, intentos
            """,
            JobRepository::fila,
            lockSegundos,
            tipo,
            workerId)
        .stream()
        .findFirst();
  }

  /**
   * Jobs EN_PROCESO de este worker con lock vencido (el proceso murió). Los que aún tienen intentos
   * vuelven a PENDIENTE; los agotados pasan a ERROR y se devuelven para marcar su item. Los
   * intentos solo los suma {@link #reclamar}.
   */
  @Transactional
  public JobsColgados liberarJobsColgados(String workerId, int maxIntentos) {
    List<JobRow> agotados =
        jdbc.query(
            """
            UPDATE app.jobs
               SET estado = 'ERROR',
                   locked_until = NULL,
                   error_log = 'Job colgado: reintentos agotados'
             WHERE estado = 'EN_PROCESO'
               AND worker_id = ?
               AND tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
               AND (locked_until IS NULL OR locked_until < NOW())
               AND intentos >= ?
            RETURNING id, tenant_id, payload::text, intentos
            """,
            JobRepository::fila,
            workerId,
            maxIntentos);
    int reencolados =
        jdbc.update(
            """
            UPDATE app.jobs
               SET estado = 'PENDIENTE',
                   locked_until = NULL,
                   run_at = NOW() + INTERVAL '15 seconds'
             WHERE estado = 'EN_PROCESO'
               AND worker_id = ?
               AND tipo IN ('PROCESAR_DOCUMENTO', 'PROCESAR_IA_EXPEDIENTE')
               AND (locked_until IS NULL OR locked_until < NOW())
               AND intentos < ?
            """,
            workerId,
            maxIntentos);
    return new JobsColgados(reencolados, agotados);
  }

  /** Espera antes del siguiente intento: 15 s, 30 s, 60 s… con tope de 5 min. */
  static long backoffSegundos(int intentos) {
    int exponente = Math.max(0, Math.min(intentos - 1, 5));
    return Math.min(300L, 15L << exponente);
  }

  private static JobRow fila(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new JobRow(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString(3),
        rs.getInt("intentos"));
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

  /** Reintento con backoff según los intentos ya consumidos. Solo si el job sigue EN_PROCESO. */
  public void devolverAPendiente(UUID id, int intentos) {
    jdbc.update(
        """
        UPDATE app.jobs
           SET estado = 'PENDIENTE',
               locked_until = NULL,
               run_at = NOW() + ? * INTERVAL '1 second'
         WHERE id = ? AND estado = 'EN_PROCESO'
        """,
        backoffSegundos(intentos),
        id);
  }

  public void completar(UUID id) {
    jdbc.update(
        """
        UPDATE app.jobs
           SET estado = 'COMPLETADO', error_log = NULL, locked_until = NULL, run_at = NOW()
         WHERE id = ? AND estado = 'EN_PROCESO'
        """,
        id);
  }

  public void error(UUID id, String errorLog) {
    String log = errorLog == null ? "" : errorLog;
    if (log.length() > 4000) {
      log = log.substring(0, 4000);
    }
    jdbc.update(
        """
        UPDATE app.jobs
           SET estado = 'ERROR', error_log = ?, locked_until = NULL, run_at = NOW()
         WHERE id = ? AND estado = 'EN_PROCESO'
        """,
        log,
        id);
  }

  public record JobRow(UUID id, UUID tenantId, String payload, int intentos) {}

  public record JobsColgados(int reencolados, List<JobRow> agotados) {}

  public record JobsEnCola(int encolados, int iaEncolados) {}
}
