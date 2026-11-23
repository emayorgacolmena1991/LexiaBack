package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaTareaSugeridaRepository extends JpaRepository<CoactivaTareaSugerida, UUID> {

  List<CoactivaTareaSugerida> findByTenantIdAndExpedienteIdOrderByCreadoEnAsc(
      UUID tenantId, UUID expedienteId);

  Optional<CoactivaTareaSugerida> findByIdAndTenantIdAndExpedienteId(
      UUID id, UUID tenantId, UUID expedienteId);

  void deleteByTenantIdAndExpedienteIdAndEstado(UUID tenantId, UUID expedienteId, String estado);
}
