package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, UUID> {

  List<DocumentRequirement> findByTenantIdAndProductCodeOrderBySortOrderAsc(
      UUID tenantId, String productCode);
}
