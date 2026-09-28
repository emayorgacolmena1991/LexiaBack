package com.lexia.api.modules.expedientes.proceso;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, UUID> {

  Optional<ProcessDefinition> findByTenantIdAndCaseTypeAndDeletedAtIsNull(UUID tenantId, String caseType);
}
