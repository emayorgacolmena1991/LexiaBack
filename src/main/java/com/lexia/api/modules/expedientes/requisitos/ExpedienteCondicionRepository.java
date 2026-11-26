package com.lexia.api.modules.expedientes.requisitos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpedienteCondicionRepository extends JpaRepository<ExpedienteCondicion, UUID> {

  /** Índice ix_expediente_condicion_expediente (tenant_id, expediente_id). */
  List<ExpedienteCondicion> findByTenantIdAndExpedienteId(UUID tenantId, UUID expedienteId);
}
