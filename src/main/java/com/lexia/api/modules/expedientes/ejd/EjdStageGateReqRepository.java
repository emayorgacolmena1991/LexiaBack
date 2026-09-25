package com.lexia.api.modules.expedientes.ejd;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EjdStageGateReqRepository extends JpaRepository<EjdStageGateReq, UUID> {

  List<EjdStageGateReq> findByTenantIdOrderByStageCodeAscSortOrderAsc(UUID tenantId);

  List<EjdStageGateReq> findByTenantIdAndStageCodeOrderBySortOrderAsc(UUID tenantId, String stageCode);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM EjdStageGateReq r WHERE r.tenantId = :tenantId AND r.stageCode = :stageCode")
  void deleteByTenantIdAndStageCode(
      @Param("tenantId") UUID tenantId, @Param("stageCode") String stageCode);
}
