package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseGateRepository extends JpaRepository<CaseGate, UUID> {

  List<CaseGate> findByCaseIdAndTenantIdOrderByGateDefIdAsc(UUID caseId, UUID tenantId);

  Optional<CaseGate> findByIdAndTenantId(UUID id, UUID tenantId);
}
