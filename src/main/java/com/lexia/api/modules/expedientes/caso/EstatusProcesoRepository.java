package com.lexia.api.modules.expedientes.caso;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EstatusProcesoRepository extends JpaRepository<EstatusProceso, UUID> {

  List<EstatusProceso> findByTenantIdAndCaseIdOrderByCreatedAtDesc(UUID tenantId, UUID caseId);

  @Query(
      """
      select e from EstatusProceso e
      where e.tenantId = :tenantId and e.caseId in :caseIds
      order by e.createdAt desc
      """)
  List<EstatusProceso> findRecientes(
      @Param("tenantId") UUID tenantId, @Param("caseIds") Collection<UUID> caseIds);
}
