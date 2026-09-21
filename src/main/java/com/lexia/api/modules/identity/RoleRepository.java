package com.lexia.api.modules.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
  List<Role> findByTenantId(UUID tenantId);

  List<Role> findByTenantIdAndDeletedAtIsNullOrderByNameAsc(UUID tenantId);

  java.util.Optional<Role> findByTenantIdAndCode(UUID tenantId, String code);

  java.util.Optional<Role> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
