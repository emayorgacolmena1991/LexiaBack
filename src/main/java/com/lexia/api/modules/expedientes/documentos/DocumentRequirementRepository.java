package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, UUID> {

  List<DocumentRequirement> findByTenantIdAndProductCodeOrderBySortOrderAsc(
      UUID tenantId, String productCode);

  /** Catálogo de tipos de escrituración del tenant: todo tipo que aparece en la matriz. */
  @org.springframework.data.jpa.repository.Query(
      "select distinct r.documentTypeCode from DocumentRequirement r where r.tenantId = :tenantId")
  List<String> tiposDelTenant(
      @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
