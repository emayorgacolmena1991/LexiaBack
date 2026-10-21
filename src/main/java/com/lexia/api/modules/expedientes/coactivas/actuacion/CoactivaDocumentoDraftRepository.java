package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaDocumentoDraftRepository extends JpaRepository<CoactivaDocumentoDraft, UUID> {

  Optional<CoactivaDocumentoDraft> findByTenantIdAndExpedienteIdAndActuacionTipo(
      UUID tenantId, UUID expedienteId, String actuacionTipo);

  Optional<CoactivaDocumentoDraft> findByIdAndTenantId(UUID id, UUID tenantId);
}
