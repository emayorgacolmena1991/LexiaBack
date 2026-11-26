package com.lexia.api.modules.expedientes.documentos;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DocumentClasificacionMemoriaService {

  private static final int MAX_EXCERPT = 500;
  private static final int MAX_EJEMPLOS = 3;

  private final EntityManager entityManager;

  public DocumentClasificacionMemoriaService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public void registrar(UUID tenantId, UUID documentId, String tipo, String source, String ocrExcerpt) {
    if (tenantId == null || documentId == null || !StringUtils.hasText(tipo)) {
      return;
    }
    entityManager
        .createNativeQuery(
            "INSERT INTO app.document_classification"
                + " (tenant_id, document_id, label, source, ocr_excerpt)"
                + " VALUES (:tenant, :doc, :label, :source, :excerpt)")
        .setParameter("tenant", tenantId)
        .setParameter("doc", documentId)
        .setParameter("label", tipo.trim().toUpperCase(Locale.ROOT))
        .setParameter("source", source)
        .setParameter("excerpt", recortar(ocrExcerpt))
        .executeUpdate();
  }

  @Transactional(readOnly = true)
  public List<String> ejemplosClasificacion(UUID tenantId, int limite) {
    if (tenantId == null || limite <= 0) {
      return List.of();
    }
    int tope = Math.min(limite, MAX_EJEMPLOS);
    @SuppressWarnings("unchecked")
    List<Object[]> filas =
        entityManager
            .createNativeQuery(
                "SELECT c.label, c.ocr_excerpt FROM app.document_classification c"
                    + " WHERE c.tenant_id = :tenant"
                    + " AND c.source IN ('MANUAL', 'AUTO')"
                    + " AND c.ocr_excerpt IS NOT NULL"
                    + " AND length(trim(c.ocr_excerpt)) > 40"
                    + " ORDER BY c.assigned_at DESC"
                    + " LIMIT :lim")
            .setParameter("tenant", tenantId)
            .setParameter("lim", tope)
            .getResultList();
    List<String> bloques = new ArrayList<>(filas.size());
    for (Object[] row : filas) {
      String tipo = row[0] == null ? "" : String.valueOf(row[0]);
      String texto = row[1] == null ? "" : String.valueOf(row[1]);
      if (!StringUtils.hasText(tipo) || !StringUtils.hasText(texto)) {
        continue;
      }
      bloques.add("<ejemplo tipo=\"" + tipo + "\">\n" + texto.trim() + "\n</ejemplo>");
    }
    return bloques;
  }

  private static String recortar(String texto) {
    if (!StringUtils.hasText(texto)) {
      return null;
    }
    String t = texto.trim();
    return t.length() <= MAX_EXCERPT ? t : t.substring(0, MAX_EXCERPT);
  }
}