package com.lexia.api.modules.expedientes.coactivas.ingesta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaActaItemRepository extends JpaRepository<CoactivaActaItem, UUID> {

  List<CoactivaActaItem> findByTenantIdAndActaIdOrderByFilaAsc(UUID tenantId, UUID actaId);

  Optional<CoactivaActaItem> findByIdAndTenantId(UUID id, UUID tenantId);

  long countByTenantIdAndActaId(UUID tenantId, UUID actaId);
}
