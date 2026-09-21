package com.lexia.api.modules.expedientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CasePartyRepository extends JpaRepository<CaseParty, UUID> {

  List<CaseParty> findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
      UUID caseId, UUID tenantId);

  Optional<CaseParty> findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
      UUID caseId, UUID tenantId, String kind);
}
