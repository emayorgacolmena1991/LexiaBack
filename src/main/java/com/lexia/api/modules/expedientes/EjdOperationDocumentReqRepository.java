package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EjdOperationDocumentReqRepository
    extends JpaRepository<EjdOperationDocumentReq, UUID> {

  List<EjdOperationDocumentReq> findByTenantIdOrderByOperationCodeAscSortOrderAsc(UUID tenantId);

  void deleteByTenantIdAndOperationCode(UUID tenantId, String operationCode);
}
