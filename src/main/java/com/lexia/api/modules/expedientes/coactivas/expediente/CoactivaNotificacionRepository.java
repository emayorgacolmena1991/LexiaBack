package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaNotificacionRepository extends JpaRepository<CoactivaNotificacion, UUID> {

  List<CoactivaNotificacion> findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByFechaAscCreatedAtAsc(
      UUID tenantId, UUID expedienteId);

  Optional<CoactivaNotificacion> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
