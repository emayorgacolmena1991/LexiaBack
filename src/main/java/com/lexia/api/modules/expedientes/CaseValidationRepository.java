package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseValidationRepository extends JpaRepository<CaseValidation, UUID> {

  List<CaseValidation> findByCaseIdAndTenantIdOrderByCreatedAtAsc(UUID caseId, UUID tenantId);
}
