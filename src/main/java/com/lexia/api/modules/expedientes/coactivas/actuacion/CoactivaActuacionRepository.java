package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaActuacionRepository extends JpaRepository<CoactivaActuacion, UUID> {

  Optional<CoactivaActuacion> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<CoactivaActuacion> findByTenantIdAndExpedienteIdAndIdempotencyKey(
      UUID tenantId, UUID expedienteId, String idempotencyKey);
}
