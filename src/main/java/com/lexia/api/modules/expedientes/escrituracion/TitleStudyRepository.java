package com.lexia.api.modules.expedientes.escrituracion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TitleStudyRepository extends JpaRepository<TitleStudy, UUID> {

  Optional<TitleStudy> findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(
      UUID writingFileId, UUID tenantId);
}
