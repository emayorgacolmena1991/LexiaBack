package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaryFirmAssignmentRepository extends JpaRepository<NotaryFirmAssignment, UUID> {

  List<NotaryFirmAssignment> findByTenantIdAndActiveTrueOrderByCreatedAtAsc(UUID tenantId);

  Optional<NotaryFirmAssignment> findByTenantIdAndNotaryTenantId(UUID tenantId, UUID notaryTenantId);
}
