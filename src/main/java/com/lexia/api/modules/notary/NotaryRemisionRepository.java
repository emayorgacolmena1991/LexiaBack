package com.lexia.api.modules.notary;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaryRemisionRepository extends JpaRepository<NotaryRemision, UUID> {

  List<NotaryRemision> findByNotaryTenantIdOrderByEmitidoEnDesc(UUID notaryTenantId);

  List<NotaryRemision> findByFirmTenantIdAndFirmLegalCaseId(
      UUID firmTenantId, UUID firmLegalCaseId);

  Optional<NotaryRemision> findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
      UUID firmTenantId, UUID firmLegalCaseId, Collection<String> estados);

  Optional<NotaryRemision> findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(
      UUID firmTenantId, UUID firmLegalCaseId);

  List<NotaryRemision> findByNotaryTenantIdAndEstadoOrderByEmitidoEnDesc(
      UUID notaryTenantId, String estado);
}
