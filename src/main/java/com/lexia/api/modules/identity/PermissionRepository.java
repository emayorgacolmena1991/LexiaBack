package com.lexia.api.modules.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
  java.util.Optional<Permission> findByCode(String code);

  java.util.List<Permission> findAllByOrderByModuleCodeAscCodeAsc();
}
