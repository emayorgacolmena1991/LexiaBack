package com.lexia.api.modules.expedientes.coactivas.expediente;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaExpedienteRepository
    extends JpaRepository<CoactivaExpediente, UUID>, JpaSpecificationExecutor<CoactivaExpediente> {

  Optional<CoactivaExpediente> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  Optional<CoactivaExpediente> findByTenantIdAndNroJuicioAndDeletedAtIsNull(UUID tenantId, String nroJuicio);

  Optional<CoactivaExpediente> findByTenantIdAndCaseIdAndDeletedAtIsNull(UUID tenantId, UUID caseId);

  List<CoactivaExpediente> findByTenantIdAndNroJuicioInAndDeletedAtIsNull(
      UUID tenantId, Collection<String> nroJuicios);

  @Query(
      "select e.semaforo, count(e) from CoactivaExpediente e "
          + "where e.tenantId = :tenantId and e.deletedAt is null group by e.semaforo")
  List<Object[]> contarPorSemaforo(@Param("tenantId") UUID tenantId);

  @Query(
      "select e.etapaVerificada, count(e) from CoactivaExpediente e "
          + "where e.tenantId = :tenantId and e.deletedAt is null group by e.etapaVerificada")
  List<Object[]> contarPorEtapa(@Param("tenantId") UUID tenantId);

  @Query(
      "select e.estadoOperativo, count(e) from CoactivaExpediente e "
          + "where e.tenantId = :tenantId and e.deletedAt is null group by e.estadoOperativo")
  List<Object[]> contarPorEstado(@Param("tenantId") UUID tenantId);
}
