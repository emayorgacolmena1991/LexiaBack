package com.lexia.api.modules.actos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActRequirementRepository extends JpaRepository<ActRequirement, UUID> {
  List<ActRequirement> findByTenantIdAndActItemIdOrderBySortOrderAsc(UUID tenantId, UUID actItemId);

  long countByTenantIdAndActItemId(UUID tenantId, UUID actItemId);
}
