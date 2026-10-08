package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseNoteRepository extends JpaRepository<CaseNote, UUID> {

  List<CaseNote> findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
      UUID caseId, UUID tenantId);
}
