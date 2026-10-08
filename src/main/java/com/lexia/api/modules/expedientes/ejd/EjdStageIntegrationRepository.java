package com.lexia.api.modules.expedientes.ejd;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EjdStageIntegrationRepository extends JpaRepository<EjdStageIntegration, UUID> {

  List<EjdStageIntegration> findByTenantIdOrderByStageCodeAscSortOrderAsc(UUID tenantId);

  List<EjdStageIntegration> findByTenantIdAndStageCodeOrderBySortOrderAsc(
      UUID tenantId, String stageCode);

  void deleteByTenantIdAndStageCode(UUID tenantId, String stageCode);
}
