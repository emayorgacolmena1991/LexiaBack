package com.lexia.api.modules.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

  List<Integration> findByTenantIdOrderByNameAsc(UUID tenantId);

  Optional<Integration> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<Integration> findByTenantIdAndCode(UUID tenantId, String code);
}
