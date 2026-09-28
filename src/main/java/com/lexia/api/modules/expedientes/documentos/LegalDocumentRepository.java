package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, UUID> {

  List<LegalDocument> findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
      UUID caseId, UUID tenantId);
}
