package com.lexia.api.modules.expedientes.coactivas.delegados;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaOficinaRepository extends JpaRepository<CoactivaOficina, UUID> {

  List<CoactivaOficina> findByTenantIdOrderBySortOrderAscNombreAsc(UUID tenantId);

  Optional<CoactivaOficina> findByTenantIdAndCodigo(UUID tenantId, String codigo);
}
