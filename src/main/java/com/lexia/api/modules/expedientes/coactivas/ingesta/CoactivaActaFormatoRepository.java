package com.lexia.api.modules.expedientes.coactivas.ingesta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaActaFormatoRepository extends JpaRepository<CoactivaActaFormato, UUID> {

  List<CoactivaActaFormato> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

  Optional<CoactivaActaFormato> findByTenantIdAndFirma(UUID tenantId, String firma);

  Optional<CoactivaActaFormato> findByIdAndTenantId(UUID id, UUID tenantId);
}
