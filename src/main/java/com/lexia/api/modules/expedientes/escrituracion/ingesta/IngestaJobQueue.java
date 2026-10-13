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
 * ponytail: al arrancar, todo EN_PROCESO vuelve a PENDIENTE. Con dos nodos se pisarían.
 */
@Repository
public class IngestaJobQueue {

  public record Job(UUID id, UUID tenantId, String tipo, String payload, int intentos) {}

  private final EntityManager entityManager;

  public IngestaJobQueue(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public void encolar(UUID tenantId, String tipo, String payload) {
    entityManager
        .createNativeQuery(
            """
            INSERT INTO app.jobs (id, tenant_id, tipo, payload)
            VALUES (:id, :tenantId, :tipo, CAST(:payload AS jsonb))
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("tenantId", tenantId)
        .setParameter("tipo", tipo)
        .setParameter("payload", payload)
        .executeUpdate();
  }

  @Transactional
  public Optional<Job> reclamar() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT id, tenant_id, tipo, CAST(payload AS text), intentos
                FROM app.jobs
                WHERE estado = 'PENDIENTE' AND run_at <= now()
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
            SET estado = 'EN_PROCESO', intentos = intentos + 1, run_at = now()
            WHERE id = :id
            """)
        .setParameter("id", id)
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
  public void completar(UUID id) {
    entityManager
        .createNativeQuery("UPDATE app.jobs SET estado = 'COMPLETADO', run_at = now() WHERE id = :id")
        .setParameter("id", id)
        .executeUpdate();
  }

  @Transactional
  public void fallar(UUID id, String error) {
    entityManager
        .createNativeQuery(
            """
            UPDATE app.jobs
            SET estado = CASE WHEN intentos >= 3 THEN 'ERROR' ELSE 'PENDIENTE' END,
                error_log = :error,
                run_at = now() + interval '15 seconds'
            WHERE id = :id
            """)
        .setParameter("id", id)
        .setParameter("error", error == null ? "error" : error.substring(0, Math.min(error.length(), 800)))
        .executeUpdate();
  }

  @Transactional
  public int reanudarInterrumpidos() {
    return entityManager
        .createNativeQuery(
            "UPDATE app.jobs SET estado = 'PENDIENTE', run_at = now() WHERE estado = 'EN_PROCESO'")
        .executeUpdate();
  }

  public static ApiException noEsJob(String tipo) {
    return ApiException.badRequest("Tipo de job desconocido: " + tipo);
  }
}
