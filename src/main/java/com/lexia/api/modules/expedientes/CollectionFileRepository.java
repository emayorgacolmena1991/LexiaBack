package com.lexia.api.modules.expedientes;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionFileRepository extends JpaRepository<CollectionFile, UUID> {

  Optional<CollectionFile> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);
}
