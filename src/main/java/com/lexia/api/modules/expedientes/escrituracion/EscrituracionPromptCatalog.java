package com.lexia.api.modules.expedientes.escrituracion;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Prompt de escrituración editable en BD. El YML queda como respaldo. */
@Repository
public class EscrituracionPromptCatalog {

  private final EntityManager entityManager;

  public EscrituracionPromptCatalog(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public String resolver(String canton, String productCode, String tipoDocumento) {
    @SuppressWarnings("unchecked")
    List<?> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT system_prompt
                FROM app.escrituracion_prompt_catalog
                WHERE activo
                  AND canton IN (:canton, '*')
                  AND product_code IN (:product, '*')
                  AND tipo_documento IN (:tipo, '*')
                ORDER BY
                  (canton <> '*') DESC,
                  (product_code <> '*') DESC,
                  (tipo_documento <> '*') DESC
                LIMIT 1
                """)
            .setParameter("canton", texto(canton))
            .setParameter("product", texto(productCode))
            .setParameter("tipo", texto(tipoDocumento))
            .getResultList();
    if (rows.isEmpty() || !StringUtils.hasText(String.valueOf(rows.get(0)))) {
      return null;
    }
    return String.valueOf(rows.get(0));
  }

  private static String texto(String value) {
    return StringUtils.hasText(value) ? value.trim() : "*";
  }
}
