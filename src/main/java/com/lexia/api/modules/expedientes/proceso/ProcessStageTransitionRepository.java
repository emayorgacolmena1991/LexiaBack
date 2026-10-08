package com.lexia.api.modules.expedientes.proceso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStageTransitionRepository extends JpaRepository<ProcessStageTransition, UUID> {

  Optional<ProcessStageTransition> findByTenantIdAndProcessDefinitionIdAndFromStageCodeAndToStageCode(
      UUID tenantId, UUID processDefinitionId, String fromStageCode, String toStageCode);

  List<ProcessStageTransition> findByTenantIdAndProcessDefinitionIdOrderByFromStageCodeAsc(
      UUID tenantId, UUID processDefinitionId);

  void deleteByTenantIdAndProcessDefinitionId(UUID tenantId, UUID processDefinitionId);
}
