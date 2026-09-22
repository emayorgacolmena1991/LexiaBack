package com.lexia.api.modules.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogRepository extends JpaRepository<Catalog, UUID> {
  List<Catalog> findByTenantIdAndDeletedAtIsNullOrderByNameAsc(UUID tenantId);

  Optional<Catalog> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  Optional<Catalog> findByTenantIdAndCodeAndDeletedAtIsNull(UUID tenantId, String code);
}
