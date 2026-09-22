package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EcdDocumentReqRepository extends JpaRepository<EcdDocumentReq, UUID> {

  List<EcdDocumentReq> findByTenantIdOrderBySortOrderAsc(UUID tenantId);

  void deleteByTenantId(UUID tenantId);
}
