package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaParticipanteRepository extends JpaRepository<CoactivaParticipante, UUID> {

  List<CoactivaParticipante> findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(
      UUID tenantId, UUID expedienteId);

  List<CoactivaParticipante> findByTenantIdAndExpedienteIdInAndDeletedAtIsNull(
      UUID tenantId, Collection<UUID> expedienteIds);

  Optional<CoactivaParticipante> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
