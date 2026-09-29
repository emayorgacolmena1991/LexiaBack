package com.lexia.api.modules.expedientes.coactivas.ingesta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaActaRepository extends JpaRepository<CoactivaActa, UUID> {

  List<CoactivaActa> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID tenantId);

  Optional<CoactivaActa> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
