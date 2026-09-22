package com.lexia.api.modules.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Pk> {
  List<RolePermission> findByRoleIdAndTenantId(UUID roleId, UUID tenantId);

  void deleteByRoleIdAndTenantId(UUID roleId, UUID tenantId);
}
