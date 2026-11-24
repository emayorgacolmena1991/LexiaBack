package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalCaseRepository
    extends JpaRepository<LegalCase, UUID>, JpaSpecificationExecutor<LegalCase> {

  List<LegalCase> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID tenantId);

  List<LegalCase> findByTenantIdAndResponsibleMembershipIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
      UUID tenantId, UUID responsibleMembershipId);

  long countByTenantIdAndDeletedAtIsNull(UUID tenantId);

  Optional<LegalCase> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

  Optional<LegalCase> findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(UUID tenantId, String code);

  boolean existsByTenantIdAndCodeAndDeletedAtIsNull(UUID tenantId, String code);

  /**
   * Expedientes de escrituración del tenant cuyo número de instancia, sin espacios, está en la lista
   * (cruce con la matriz de la entidad). Siempre filtrado por tenant, además de RLS.
   */
  @Query(
      value =
          """
          SELECT * FROM app.legal_case
          WHERE tenant_id = :tenantId
            AND deleted_at IS NULL
            AND case_type = 'EJD'
            AND numero_instancia IS NOT NULL
            AND regexp_replace(numero_instancia, '[[:space:]]', '', 'g') IN (:numeros)
          """,
      nativeQuery = true)
  List<LegalCase> findEscrituracionPorNumerosInstancia(
      @Param("tenantId") UUID tenantId, @Param("numeros") java.util.Collection<String> numeros);

  /** Usa legal_case_numero_instancia_idx (V117). */
  List<LegalCase> findByTenantIdAndNumeroInstanciaAndDeletedAtIsNull(UUID tenantId, String numeroInstancia);

  long countByTenantIdAndCaseTypeAndDeletedAtIsNullAndProcessConfigVersionIsNot(
      UUID tenantId, String caseType, int configVersion);
}
