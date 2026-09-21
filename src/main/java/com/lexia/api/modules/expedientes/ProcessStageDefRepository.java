package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStageDefRepository extends JpaRepository<ProcessStageDef, UUID> {

  List<ProcessStageDef> findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
      UUID processDefinitionId, UUID tenantId);

  Optional<ProcessStageDef> findByIdAndTenantId(UUID id, UUID tenantId);
}
