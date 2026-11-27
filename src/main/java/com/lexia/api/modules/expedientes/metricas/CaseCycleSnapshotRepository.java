package com.lexia.api.modules.expedientes.metricas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseCycleSnapshotRepository extends JpaRepository<CaseCycleSnapshot, UUID> {

  Optional<CaseCycleSnapshot> findByTenantIdAndCaseId(UUID tenantId, UUID caseId);

  List<CaseCycleSnapshot> findByTenantIdOrderByCompletedAtDesc(UUID tenantId, Pageable pageable);

  long countByTenantId(UUID tenantId);
}