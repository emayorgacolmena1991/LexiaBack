package com.lexia.api.modules.expedientes;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessConfigChangeSetRepository
    extends JpaRepository<ProcessConfigChangeSet, UUID> {

  Optional<ProcessConfigChangeSet>
      findFirstByTenantIdAndProcessDefinitionIdAndStatusInOrderByCreatedAtDesc(
          UUID tenantId, UUID processDefinitionId, Collection<String> statuses);
}
