package com.lexia.api.modules.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantModuleRepository extends JpaRepository<TenantModule, UUID> {

  List<TenantModule> findByTenantIdAndEnabledTrueOrderByModuleCodeAsc(UUID tenantId);
}
