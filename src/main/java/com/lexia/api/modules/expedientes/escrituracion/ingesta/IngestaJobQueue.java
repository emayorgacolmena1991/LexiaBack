package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.lexia.api.common.api.ApiException;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cola en Postgres. {@code app.jobs} no tiene RLS: el worker reclama de cualquier tenant
 * y luego fija {@code app.current_tenant_id}.
 * El candado {@code locked_until} evita que otro nodo reclame un job vivo.
 */
@Repository
public class IngestaJobQueue {

  public record Job(UUID id, UUID tenantId, String tipo, String payload, int intentos) {}

  private final EntityManager entityManager;

  public IngestaJobQueue(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public UUID encolar(UUID tenantId, String tipo, String payload) {
    UUID id = UUID.randomUUID();
    entityManager
        .createNativeQuery(
            """
            INSERT INTO app.jobs (id, tenant_id, tipo, payload)
            VALUES (:id, :tenantId, :tipo, CAST(:payload AS jsonb))
            """)
        .setParameter("id", id)
        .setParameter("tenantId", tenantId)
        .setParameter("tipo", tipo)
        .setParameter("payload", payload)
        .executeUpdate();
    return id;
  }

  @Transactional
  public Optional<Job> reclamar(String workerId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT id, tenant_id, tipo, CAST(payload AS text), intentos
                FROM app.jobs
                WHERE (estado = 'PENDIENTE' AND run_at <= now())
                   OR (estado = 'EN_PROCESO' AND (locked_until IS NULL OR locked_until < now()))
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    UUID id = UUID.fromString(String.valueOf(row[0]));
    entityManager
        .createNativeQuery(
            """
            UPDATE app.jobs
            SET estado = 'EN_PROCESO',
                worker_id = :worker,
                locked_until = now() + interval '2 minutes',
                intentos = intentos + 1,
                run_at = now()
            WHERE id = :id
            """)
        .setParameter("id", id)
        .setParameter("worker", workerId)
        .executeUpdate();
    return Optional.of(
        new Job(
            id,
            UUID.fromString(String.valueOf(row[1])),
            String.valueOf(row[2]),
            String.valueOf(row[3]),
            ((Number) row[4]).intValue() + 1));
  }

  @Transactional
  public void completar(UUID id, String workerId) {
    entityManager
        .createNativeQuery(
            """
            UPDATE app.jobs
            SET estado = 'COMPLETADO', run_at = now(), locked_until = NULL
            WHERE id = :id AND worker_id = :worker
            """)
        .setParameter("id", id)
        .setParameter("worker", workerId)
        .executeUpdate();
  }

  @Transactional
  public void fallar(UUID id, String workerId, String error) {
    entityManager
        .createNativeQuery(
            """
            UPDATE app.jobs
            SET estado = CASE WHEN intentos >= 3 THEN 'ERROR' ELSE 'PENDIENTE' END,
                error_log = :error,
                worker_id = NULL,
                locked_until = NULL,
                run_at = now() + interval '15 seconds'
            WHERE id = :id AND worker_id = :worker
            """)
        .setParameter("id", id)
        .setParameter("worker", workerId)
        .setParameter("error", error == null ? "error" : error.substring(0, Math.min(error.length(), 800)))
        .executeUpdate();
  }

  @Transactional
  public void latido(String workerId) {
    entityManager
        .createNativeQuery(
            """
            UPDATE app.jobs
            SET locked_until = now() + interval '2 minutes'
            WHERE worker_id = :worker AND estado = 'EN_PROCESO'
            """)
        .setParameter("worker", workerId)
        .executeUpdate();
  }

  public static ApiException noEsJob(String tipo) {
    return ApiException.badRequest("Tipo de job desconocido: " + tipo);
  }
}
