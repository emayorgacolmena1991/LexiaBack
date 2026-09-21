package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractedDataRepository extends JpaRepository<ExtractedData, UUID> {

  List<ExtractedData> findByCaseIdAndTenantIdOrderByFieldLabelAsc(UUID caseId, UUID tenantId);
}
