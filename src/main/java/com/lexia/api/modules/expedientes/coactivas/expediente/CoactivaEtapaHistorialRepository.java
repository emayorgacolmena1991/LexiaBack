package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaEtapaHistorialRepository extends JpaRepository<CoactivaEtapaHistorial, UUID> {

  List<CoactivaEtapaHistorial> findByTenantIdAndExpedienteIdOrderByCreatedAtAsc(UUID tenantId, UUID expedienteId);
}
