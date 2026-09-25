package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseExceptionRepository extends JpaRepository<CaseException, UUID> {

  List<CaseException> findByCaseIdAndTenantIdOrderByCreatedAtDesc(UUID caseId, UUID tenantId);

  Optional<CaseException> findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(
      UUID caseId, UUID tenantId, String exceptionType, String title, String status);
}
