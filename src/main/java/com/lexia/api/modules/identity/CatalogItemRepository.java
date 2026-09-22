package com.lexia.api.modules.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, UUID> {
  List<CatalogItem> findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(
      UUID catalogId, UUID tenantId);

  Optional<CatalogItem> findByIdAndTenantId(UUID id, UUID tenantId);

  long countByCatalogIdAndTenantId(UUID catalogId, UUID tenantId);

  long countByCatalogIdAndTenantIdAndActiveTrue(UUID catalogId, UUID tenantId);

  boolean existsByCatalogIdAndTenantIdAndCodeIgnoreCase(UUID catalogId, UUID tenantId, String code);

  boolean existsByCatalogIdAndTenantIdAndCodeIgnoreCaseAndIdNot(
      UUID catalogId, UUID tenantId, String code, UUID id);
}
