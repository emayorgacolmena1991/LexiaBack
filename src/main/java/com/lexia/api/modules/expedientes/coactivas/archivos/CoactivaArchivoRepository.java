package com.lexia.api.modules.expedientes.coactivas.archivos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaArchivoRepository extends JpaRepository<CoactivaArchivo, UUID> {

  Optional<CoactivaArchivo> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  List<CoactivaArchivo> findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(
      UUID tenantId, UUID expedienteId);

  List<CoactivaArchivo> findByTenantIdAndEstadoVinculoAndDeletedAtIsNullOrderByCreatedAtDesc(
      UUID tenantId, String estadoVinculo);

  boolean existsByTenantIdAndExpedienteIdAndSha256AndDeletedAtIsNull(
      UUID tenantId, UUID expedienteId, String sha256);

  @Query(
      "select a.expedienteId, count(a) from CoactivaArchivo a "
          + "where a.tenantId = :tenantId and a.deletedAt is null and a.expedienteId in :ids "
          + "group by a.expedienteId")
  List<Object[]> contarPorExpediente(
      @Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> expedienteIds);
}
