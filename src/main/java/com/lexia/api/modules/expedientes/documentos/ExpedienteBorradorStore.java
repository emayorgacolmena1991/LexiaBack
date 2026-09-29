package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Inserta con {@code gen_random_uuid()} y lee la fila para el paso 2. */
@Repository
public class ExpedienteBorradorStore {

  private final EntityManager entityManager;

  public ExpedienteBorradorStore(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public UUID insert(
      UUID tenantId,
      String idActo,
      String productCode,
      String canton,
      String ingestionMode,
      UUID createdBy) {
    Object raw =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO app.expediente_borrador
                    (tenant_id, id_acto, product_code, canton, ingestion_mode, created_by)
                VALUES (
                    :tenantId,
                    NULLIF(:idActo, ''),
                    NULLIF(:productCode, ''),
                    NULLIF(:canton, ''),
                    :ingestionMode,
                    :createdBy)
                RETURNING id
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("idActo", idActo == null ? "" : idActo)
            .setParameter("productCode", productCode == null ? "" : productCode)
            .setParameter("canton", canton == null ? "" : canton)
            .setParameter("ingestionMode", ingestionMode)
            .setParameter("createdBy", createdBy)
            .getSingleResult();
    return asUuid(raw);
  }

  @Transactional(readOnly = true)
  public Optional<ExpedienteBorrador> find(UUID id, UUID tenantId) {
    ExpedienteBorrador row = entityManager.find(ExpedienteBorrador.class, id);
    if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
      return Optional.empty();
    }
    return Optional.of(row);
  }

  /** Persiste el estudio IA en el borrador. La fila ya está en el persistence context. */
  @Transactional
  public void guardarEstudio(UUID id, UUID tenantId, String json) {
    ExpedienteBorrador row = entityManager.find(ExpedienteBorrador.class, id);
    if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
      return;
    }
    row.setDatosExtraidos(json);
  }

  @Transactional
  public void marcarPromovido(UUID id, UUID tenantId, UUID caseId) {
    ExpedienteBorrador row = entityManager.find(ExpedienteBorrador.class, id);
    if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
      return;
    }
    row.setPromotedCaseId(caseId);
    row.setStatus("PROMOVIDO");
  }

  private static UUID asUuid(Object raw) {
    if (raw instanceof UUID uuid) {
      return uuid;
    }
    if (raw instanceof Object[] row && row.length > 0 && row[0] != null) {
      return asUuid(row[0]);
    }
    return UUID.fromString(String.valueOf(raw));
  }
}
