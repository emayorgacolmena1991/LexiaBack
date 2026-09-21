package com.lexia.api.modules.expedientes;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleDefRepository extends JpaRepository<RuleDef, UUID> {

  Optional<RuleDef> findByIdAndTenantId(UUID id, UUID tenantId);

  List<RuleDef> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

  List<RuleDef> findByTenantIdOrderByCodeAscVersionDesc(UUID tenantId);

  List<RuleDef> findByTenantIdAndCodeOrderByVersionDesc(UUID tenantId, String code);

  Optional<RuleDef> findFirstByTenantIdAndCodeAndStatusOrderByVersionDesc(
      UUID tenantId, String code, String status);
}
