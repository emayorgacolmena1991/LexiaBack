package com.lexia.api.modules.expedientes.proceso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessConfigPublicationRepository
    extends JpaRepository<ProcessConfigPublication, UUID> {

  List<ProcessConfigPublication> findByTenantIdAndProcessDefinitionIdOrderByPublishedAtDesc(
      UUID tenantId, UUID processDefinitionId);

  Optional<ProcessConfigPublication> findByTenantIdAndProcessDefinitionIdAndConfigVersion(
      UUID tenantId, UUID processDefinitionId, int configVersion);
}
