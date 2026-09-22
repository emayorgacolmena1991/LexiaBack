package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseStageRepository extends JpaRepository<CaseStage, UUID> {

  List<CaseStage> findByCaseIdAndTenantIdOrderByStartedAtAsc(UUID caseId, UUID tenantId);

  Optional<CaseStage> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<CaseStage> findByCaseIdAndTenantIdAndStatus(UUID caseId, UUID tenantId, String status);
}
