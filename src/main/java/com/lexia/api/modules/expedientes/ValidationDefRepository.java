package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationDefRepository extends JpaRepository<ValidationDef, UUID> {

  Optional<ValidationDef> findByIdAndTenantId(UUID id, UUID tenantId);

  List<ValidationDef> findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
      UUID processDefinitionId, UUID tenantId);

  List<ValidationDef> findByTenantIdAndRuleDefId(UUID tenantId, UUID ruleDefId);
}
