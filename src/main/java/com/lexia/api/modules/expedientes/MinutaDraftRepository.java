package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MinutaDraftRepository extends JpaRepository<MinutaDraft, UUID> {

  List<MinutaDraft> findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(
      UUID writingFileId, UUID tenantId);
}
