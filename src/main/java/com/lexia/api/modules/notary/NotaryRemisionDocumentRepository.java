package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaryRemisionDocumentRepository
    extends JpaRepository<NotaryRemisionDocument, UUID> {

  List<NotaryRemisionDocument> findByRemisionIdOrderBySortOrderAsc(UUID remisionId);

  Optional<NotaryRemisionDocument> findByRemisionIdAndId(UUID remisionId, UUID id);
}
