package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

  Optional<DocumentVersion> findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(
      UUID documentId, UUID tenantId);

  List<DocumentVersion> findByDocumentIdAndTenantIdOrderByVersionNoDesc(
      UUID documentId, UUID tenantId);
}
