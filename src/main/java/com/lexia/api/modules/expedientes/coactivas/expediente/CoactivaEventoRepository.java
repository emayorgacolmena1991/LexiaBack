package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaEventoRepository extends JpaRepository<CoactivaEvento, UUID> {

  List<CoactivaEvento> findByTenantIdAndExpedienteIdOrderByCreatedAtAsc(UUID tenantId, UUID expedienteId);
}
