package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.auth.AuthContext;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentoCacheStore {

  public static final String VERSION_OCR = "ocr-v1";

  public record Hit(String modelo, String textoOcr, String resultadoJson) {}

  private final EntityManager entityManager;

  public DocumentoCacheStore(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String sha256(String texto) {
    return sha256((texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8));
  }

  @Transactional(readOnly = true)
  public Optional<Hit> buscar(String sha256, String versionPrompt) {
    return buscar(tenant(), sha256, versionPrompt);
  }

  /** Para workers de {@code app.jobs}, que no tienen {@link AuthContext}. */
  @Transactional(readOnly = true)
  public Optional<Hit> buscar(UUID tenantId, String sha256, String versionPrompt) {
    if (tenantId == null || sha256 == null) {
      return Optional.empty();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT modelo, texto_ocr, resultado_json
                FROM app.documento_cache
                WHERE tenant_id = :tenantId AND sha256 = :sha AND version_prompt = :version
                ORDER BY created_at DESC
                LIMIT 1
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("sha", sha256)
            .setParameter("version", versionPrompt)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    return Optional.of(new Hit(str(row[0]), str(row[1]), str(row[2])));
  }

  @Transactional
  public void guardar(String sha256, String versionPrompt, String modelo, String texto, String json) {
    guardar(tenant(), sha256, versionPrompt, modelo, texto, json);
  }

  @Transactional
  public void guardar(
      UUID tenantId, String sha256, String versionPrompt, String modelo, String texto, String json) {
    if (tenantId == null || sha256 == null) {
      return;
    }
    entityManager
        .createNativeQuery(
            """
            INSERT INTO app.documento_cache
              (id, tenant_id, sha256, version_prompt, modelo, texto_ocr, resultado_json)
            VALUES (:id, :tenantId, :sha, :version, :modelo, :texto, :json)
            ON CONFLICT (tenant_id, sha256, version_prompt, modelo) DO UPDATE SET
              texto_ocr = COALESCE(EXCLUDED.texto_ocr, app.documento_cache.texto_ocr),
              resultado_json = COALESCE(EXCLUDED.resultado_json, app.documento_cache.resultado_json)
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("tenantId", tenantId)
        .setParameter("sha", sha256)
        .setParameter("version", versionPrompt)
        .setParameter("modelo", modelo)
        .setParameter("texto", texto)
        .setParameter("json", json)
        .executeUpdate();
  }

  private static UUID tenant() {
    return AuthContext.get() == null ? null : AuthContext.get().tenantId();
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
