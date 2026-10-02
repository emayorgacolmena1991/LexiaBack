package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaMedidaRepository extends JpaRepository<CoactivaMedida, UUID> {

  List<CoactivaMedida> findByTenantIdAndExpedienteIdOrderByCreatedAtDesc(UUID tenantId, UUID expedienteId);
}
