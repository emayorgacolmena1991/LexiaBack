package com.lexia.api.modules.expedientes.coactivas.delegados;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaDelegadoRepository extends JpaRepository<CoactivaDelegado, UUID> {

  List<CoactivaDelegado> findByTenantIdAndDeletedAtIsNullOrderByNombreAsc(UUID tenantId);

  Optional<CoactivaDelegado> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  List<CoactivaDelegado> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
