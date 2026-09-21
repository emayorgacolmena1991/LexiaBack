package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

  List<SlaPolicy> findByTenantIdOrderByCodeAsc(UUID tenantId);
}
