package com.lexia.api.modules.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantParameterRepository extends JpaRepository<TenantParameter, UUID> {
  Optional<TenantParameter> findByTenantIdAndParamKey(UUID tenantId, String paramKey);
}
