package com.lexia.api.modules.expedientes.caso;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseGateRepository extends JpaRepository<CaseGate, UUID> {

  List<CaseGate> findByCaseIdAndTenantIdOrderByGateDefIdAsc(UUID caseId, UUID tenantId);

  @Query(
      """
      select distinct g.caseId from CaseGate g
      where g.tenantId = :tenantId
        and g.caseId in :caseIds
        and g.result in ('REVIEW_REQUIRED', 'NOT_EVALUATED')
      """)
  List<UUID> findCaseIdsWithPendingGates(
      @Param("tenantId") UUID tenantId, @Param("caseIds") Collection<UUID> caseIds);

  Optional<CaseGate> findByIdAndTenantId(UUID id, UUID tenantId);
}
