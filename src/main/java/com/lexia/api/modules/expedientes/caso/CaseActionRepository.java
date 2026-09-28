package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseActionRepository extends JpaRepository<CaseAction, UUID> {

  List<CaseAction> findByCaseIdAndTenantIdOrderByCreatedAtDesc(UUID caseId, UUID tenantId);
}
