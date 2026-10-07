package com.lexia.api.modules.expedientes.coactivas.embargo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaEmbargoLoteRepository extends JpaRepository<CoactivaEmbargoLote, UUID> {

  @Query("select l from CoactivaEmbargoLote l where l.tenantId = :tenantId and l.estado = 'EN_PREPARACION'")
  Optional<CoactivaEmbargoLote> activo(@Param("tenantId") UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from CoactivaEmbargoLote l where l.tenantId = :tenantId and l.estado = 'EN_PREPARACION'")
  Optional<CoactivaEmbargoLote> bloquearActivo(@Param("tenantId") UUID tenantId);

  @Query("select max(l.fechaCorte) from CoactivaEmbargoLote l where l.tenantId = :tenantId")
  Optional<Instant> ultimoCorte(@Param("tenantId") UUID tenantId);

  @Query("select coalesce(max(l.numero), 0) from CoactivaEmbargoLote l where l.tenantId = :tenantId")
  int ultimoNumero(@Param("tenantId") UUID tenantId);

  /** Dos usuarios que abren el lote a la vez: el índice parcial deja uno solo y el otro no inserta. */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO app.coactiva_embargo_lote (id, tenant_id, numero, estado, fecha_corte, created_by)
          VALUES (:id, :tenantId, :numero, 'EN_PREPARACION', :fechaCorte, :userId)
          ON CONFLICT (tenant_id) WHERE estado = 'EN_PREPARACION' DO NOTHING
          """,
      nativeQuery = true)
  int abrir(
      @Param("id") UUID id,
      @Param("tenantId") UUID tenantId,
      @Param("numero") int numero,
      @Param("fechaCorte") Instant fechaCorte,
      @Param("userId") UUID userId);
}
