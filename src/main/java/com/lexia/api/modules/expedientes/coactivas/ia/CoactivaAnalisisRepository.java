package com.lexia.api.modules.expedientes.coactivas.ia;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaAnalisisRepository extends JpaRepository<CoactivaAnalisis, UUID> {

  Optional<CoactivaAnalisis> findFirstByTenantIdAndExpedienteIdOrderByCreatedAtDesc(
      UUID tenantId, UUID expedienteId);

  Optional<CoactivaAnalisis> findFirstByTenantIdAndExpedienteIdAndEstadoOrderByCreatedAtDesc(
      UUID tenantId, UUID expedienteId, String estado);

  /** {@code app.jobs} no tiene RLS: el filtro por tenant es obligatorio. */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1 FROM app.jobs
            WHERE tenant_id = :tenantId AND tipo = 'COACTIVA_DIAGNOSTICO'
              AND estado IN ('PENDIENTE', 'EN_PROCESO')
              AND payload->>'expedienteId' = :expedienteId)
          """,
      nativeQuery = true)
  boolean hayDiagnosticoActivo(@Param("tenantId") UUID tenantId, @Param("expedienteId") String expedienteId);
}
