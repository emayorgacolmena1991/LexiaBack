package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, UUID> {

  List<LegalDocument> findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
      UUID caseId, UUID tenantId);

  List<LegalDocument> findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
      UUID caseId, UUID tenantId);

  java.util.Optional<LegalDocument> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
