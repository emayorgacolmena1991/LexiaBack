package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantFeatureFlagRepository extends JpaRepository<TenantFeatureFlag, UUID> {

  List<TenantFeatureFlag> findByTenantIdOrderByCodeAsc(UUID tenantId);

  Optional<TenantFeatureFlag> findByTenantIdAndCode(UUID tenantId, String code);
}
