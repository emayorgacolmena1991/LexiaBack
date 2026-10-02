package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaPlantillaRepository extends JpaRepository<CoactivaPlantilla, UUID> {

  List<CoactivaPlantilla> findByTenantIdAndEtapaInAndActivoTrueOrderByNombreAsc(
      UUID tenantId, Collection<String> etapas);

  Optional<CoactivaPlantilla> findByIdAndTenantIdAndActivoTrue(UUID id, UUID tenantId);
}
