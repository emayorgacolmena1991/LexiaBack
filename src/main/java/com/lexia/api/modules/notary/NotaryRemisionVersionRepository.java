package com.lexia.api.modules.notary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaryRemisionVersionRepository
    extends JpaRepository<NotaryRemisionVersion, UUID> {

  Optional<NotaryRemisionVersion> findFirstByRemisionIdOrderByVersionNoDesc(UUID remisionId);

  Optional<NotaryRemisionVersion> findByRemisionIdAndVersionNo(UUID remisionId, int versionNo);

  List<NotaryRemisionVersion> findByRemisionIdOrderByVersionNoDesc(UUID remisionId);
}
