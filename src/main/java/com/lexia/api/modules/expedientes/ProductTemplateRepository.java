package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTemplateRepository extends JpaRepository<ProductTemplate, UUID> {

  List<ProductTemplate> findByTenantIdAndProductCodeAndActiveTrueOrderBySortOrderAsc(
      UUID tenantId, String productCode);
}
