package com.lexia.api.modules.expedientes.coactivas.embargo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaEmbargoLoteRepository extends JpaRepository<CoactivaEmbargoLote, UUID> {

  @Query(
      """
      select l from CoactivaEmbargoLote l
      where l.tenantId = :tenantId and l.delegadoId = :delegadoId and l.estado = 'EN_PREPARACION'
      """)
  Optional<CoactivaEmbargoLote> activo(@Param("tenantId") UUID tenantId, @Param("delegadoId") UUID delegadoId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select l from CoactivaEmbargoLote l
      where l.tenantId = :tenantId and l.delegadoId = :delegadoId and l.estado = 'EN_PREPARACION'
      """)
  Optional<CoactivaEmbargoLote> bloquearActivo(
      @Param("tenantId") UUID tenantId, @Param("delegadoId") UUID delegadoId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from CoactivaEmbargoLote l where l.id = :id and l.tenantId = :tenantId")
  Optional<CoactivaEmbargoLote> bloquear(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

  Optional<CoactivaEmbargoLote> findByIdAndTenantId(UUID id, UUID tenantId);

  List<CoactivaEmbargoLote> findByTenantIdAndDelegadoIdAndEstadoOrderByEntregadoAtDescNumeroDesc(
      UUID tenantId, UUID delegadoId, String estado);

  @Query(
      """
      select max(l.fechaCorte) from CoactivaEmbargoLote l
      where l.tenantId = :tenantId and l.delegadoId = :delegadoId
      """)
  Optional<Instant> ultimoCorte(@Param("tenantId") UUID tenantId, @Param("delegadoId") UUID delegadoId);

  @Query("select coalesce(max(l.numero), 0) from CoactivaEmbargoLote l where l.tenantId = :tenantId")
  int ultimoNumero(@Param("tenantId") UUID tenantId);

  /**
   * Dos usuarios del mismo delegado que abren el lote a la vez: el índice parcial deja uno solo y el
   * otro no inserta. Otro delegado no entra en ese conflicto.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO app.coactiva_embargo_lote
              (id, tenant_id, delegado_id, delegado_nombre, numero, estado, fecha_corte, created_by)
          VALUES
              (:id, :tenantId, :delegadoId, :delegadoNombre, :numero, 'EN_PREPARACION', :fechaCorte, :userId)
          ON CONFLICT (tenant_id, delegado_id) WHERE estado = 'EN_PREPARACION' DO NOTHING
          """,
      nativeQuery = true)
  int abrir(
      @Param("id") UUID id,
      @Param("tenantId") UUID tenantId,
      @Param("delegadoId") UUID delegadoId,
      @Param("delegadoNombre") String delegadoNombre,
      @Param("numero") int numero,
      @Param("fechaCorte") Instant fechaCorte,
      @Param("userId") UUID userId);
}
