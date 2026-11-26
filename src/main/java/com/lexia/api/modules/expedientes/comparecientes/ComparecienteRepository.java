package com.lexia.api.modules.expedientes.comparecientes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComparecienteRepository extends JpaRepository<Compareciente, UUID> {

  /** Índice ix_expediente_compareciente_expediente. */
  List<Compareciente> findByTenantIdAndExpedienteIdOrderByParteAscOrdenAsc(
      UUID tenantId, UUID expedienteId);

  Optional<Compareciente> findByIdAndTenantIdAndExpedienteId(
      UUID id, UUID tenantId, UUID expedienteId);
}
