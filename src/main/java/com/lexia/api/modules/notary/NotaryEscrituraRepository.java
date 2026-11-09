package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaryEscrituraRepository extends JpaRepository<NotaryEscritura, UUID> {

  List<NotaryEscritura> findByRemisionIdOrderByGeneratedAtDesc(UUID remisionId);

  Optional<NotaryEscritura> findByIdAndRemisionId(UUID id, UUID remisionId);
}
