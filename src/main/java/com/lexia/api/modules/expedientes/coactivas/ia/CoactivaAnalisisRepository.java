package com.lexia.api.modules.expedientes.coactivas.ia;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaAnalisisRepository extends JpaRepository<CoactivaAnalisis, UUID> {

  Optional<CoactivaAnalisis> findFirstByTenantIdAndExpedienteIdOrderByCreatedAtDesc(
      UUID tenantId, UUID expedienteId);
}
