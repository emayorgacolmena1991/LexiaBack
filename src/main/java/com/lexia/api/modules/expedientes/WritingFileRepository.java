package com.lexia.api.modules.expedientes;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WritingFileRepository extends JpaRepository<WritingFile, UUID> {

  Optional<WritingFile> findByCaseIdAndTenantIdAndDeletedAtIsNull(UUID caseId, UUID tenantId);
}
