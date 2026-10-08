package com.lexia.api.modules.expedientes.proceso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessConfigChangeSetItemRepository
    extends JpaRepository<ProcessConfigChangeSetItem, UUID> {

  List<ProcessConfigChangeSetItem> findByChangeSetIdOrderByDomainAsc(UUID changeSetId);

  Optional<ProcessConfigChangeSetItem> findByChangeSetIdAndDomain(
      UUID changeSetId, String domain);
}
