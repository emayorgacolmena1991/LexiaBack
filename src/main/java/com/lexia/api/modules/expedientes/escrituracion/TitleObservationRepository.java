package com.lexia.api.modules.expedientes.escrituracion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TitleObservationRepository extends JpaRepository<TitleObservation, UUID> {

  Optional<TitleObservation> findByIdAndTenantId(UUID id, UUID tenantId);

  List<TitleObservation> findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(
      UUID titleStudyId, UUID tenantId);

  void deleteByTitleStudyIdAndTenantId(UUID titleStudyId, UUID tenantId);
}
