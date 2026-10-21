package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaSolicitudRepository extends JpaRepository<CoactivaSolicitud, UUID> {

  Optional<CoactivaSolicitud> findByTenantIdAndExpedienteIdAndOperacionAndIdempotencyKey(
      UUID tenantId, UUID expedienteId, String operacion, String idempotencyKey);
}
