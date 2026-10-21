package com.lexia.api.modules.expedientes.coactivas.embargo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaEmbargoRegistroRepository extends JpaRepository<CoactivaEmbargoRegistro, UUID> {

  List<CoactivaEmbargoRegistro> findByTenantIdAndLoteIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID loteId);

  Optional<CoactivaEmbargoRegistro> findByTenantIdAndLoteIdAndExpedienteId(
      UUID tenantId, UUID loteId, UUID expedienteId);

  long countByTenantIdAndLoteId(UUID tenantId, UUID loteId);

  /**
   * Inserta la fila o, si el expediente ya está en el lote, la actualiza solo cuando {@code version}
   * coincide con {@code row_version}. Devuelve 0 cuando otro usuario la modificó antes (o la creó
   * mientras este usuario la veía como nueva: {@code version = -1}).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO app.coactiva_embargo_registro AS r (
              id, tenant_id, lote_id, expediente_id,
              juzgado, oficina_origen_credito, operacion, numero_juicio, nombre_coactivado,
              nombre_titular_operacion, valor_transferido, fecha_proceso, nombre_der_sac,
              numero_oficio_respuesta, numero_documento, created_by, updated_by)
          VALUES (
              :id, :tenantId, :loteId, :expedienteId,
              CAST(:juzgado AS varchar), CAST(:oficina AS varchar), CAST(:operacion AS varchar),
              CAST(:juicio AS varchar), CAST(:coactivado AS varchar), CAST(:titular AS varchar),
              CAST(:valor AS numeric), CAST(:fecha AS date), CAST(:derSac AS varchar),
              CAST(:oficio AS varchar), CAST(:documento AS varchar), :userId, :userId)
          ON CONFLICT (lote_id, expediente_id) DO UPDATE SET
              juzgado = EXCLUDED.juzgado,
              oficina_origen_credito = EXCLUDED.oficina_origen_credito,
              operacion = EXCLUDED.operacion,
              numero_juicio = EXCLUDED.numero_juicio,
              nombre_coactivado = EXCLUDED.nombre_coactivado,
              nombre_titular_operacion = EXCLUDED.nombre_titular_operacion,
              valor_transferido = EXCLUDED.valor_transferido,
              fecha_proceso = EXCLUDED.fecha_proceso,
              nombre_der_sac = EXCLUDED.nombre_der_sac,
              numero_oficio_respuesta = EXCLUDED.numero_oficio_respuesta,
              numero_documento = EXCLUDED.numero_documento,
              updated_by = EXCLUDED.updated_by,
              updated_at = now(),
              row_version = r.row_version + 1
          WHERE r.row_version = :version
          """,
      nativeQuery = true)
  int upsert(
      @Param("id") UUID id,
      @Param("tenantId") UUID tenantId,
      @Param("loteId") UUID loteId,
      @Param("expedienteId") UUID expedienteId,
      @Param("juzgado") String juzgado,
      @Param("oficina") String oficina,
      @Param("operacion") String operacion,
      @Param("juicio") String juicio,
      @Param("coactivado") String coactivado,
      @Param("titular") String titular,
      @Param("valor") BigDecimal valor,
      @Param("fecha") LocalDate fecha,
      @Param("derSac") String derSac,
      @Param("oficio") String oficio,
      @Param("documento") String documento,
      @Param("userId") UUID userId,
      @Param("version") long version);
}
