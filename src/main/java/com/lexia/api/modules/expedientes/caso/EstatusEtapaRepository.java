package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstatusEtapaRepository extends JpaRepository<EstatusEtapa, UUID> {

  /** Usa estatus_etapa_activas_idx (V117). */
  List<EstatusEtapa> findByTenantIdAndActivoTrueOrderByOrdenAsc(UUID tenantId);

  /** Incluye inactivas: el historial conserva el nombre de una etapa que se dio de baja. */
  List<EstatusEtapa> findByTenantId(UUID tenantId);

  Optional<EstatusEtapa> findByTenantIdAndCodigoAndActivoTrue(UUID tenantId, String codigo);
}
