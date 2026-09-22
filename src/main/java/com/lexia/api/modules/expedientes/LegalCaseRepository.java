package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalCaseRepository extends JpaRepository<LegalCase, UUID> {

  List<LegalCase> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID tenantId);

  long countByTenantIdAndDeletedAtIsNull(UUID tenantId);

  Optional<LegalCase> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  boolean existsByTenantIdAndCodeAndDeletedAtIsNull(UUID tenantId, String code);

  long countByTenantIdAndCaseTypeAndDeletedAtIsNullAndProcessConfigVersionIsNot(
      UUID tenantId, String caseType, int configVersion);
}
