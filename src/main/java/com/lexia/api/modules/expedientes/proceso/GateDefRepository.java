package com.lexia.api.modules.expedientes.proceso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateDefRepository extends JpaRepository<GateDef, UUID> {

  List<GateDef> findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
      UUID processDefinitionId, UUID tenantId);

  Optional<GateDef> findByIdAndTenantId(UUID id, UUID tenantId);
}
