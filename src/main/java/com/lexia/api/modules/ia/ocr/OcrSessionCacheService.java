package com.lexia.api.modules.ia.ocr;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Caché in-memory de sesión OCR (sin DB). TTL default 2h.
 * Clave consolidada: {@code session:{sessionId}:ocr_text}
 */
@Service
public class OcrSessionCacheService {

  public static final String CACHE_KEY_PREFIX = "session:";
  public static final String CACHE_KEY_SUFFIX = ":ocr_text";

  private final Duration ttl;
  private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

  public OcrSessionCacheService(
      @Value("${lexia.ocr.cache-ttl-hours:2}") int ttlHours) {
    this.ttl = Duration.ofHours(Math.max(1, ttlHours));
  }

  public static String cacheKey(String sessionId) {
    return CACHE_KEY_PREFIX + sessionId + CACHE_KEY_SUFFIX;
  }

  /**
   * Clave interna tenant + sesión: el mapa es de todo el nodo y el sessionId lo manda el cliente.
   * Sin tenant (tareas internas, tests) va a un espacio propio que ningún request autenticado ve.
   */
  private static String clave(String sessionId) {
    AuthPrincipal principal = AuthContext.get();
    UUID tenant =
        principal != null && principal.tenantId() != null
            ? principal.tenantId()
            : TenantContext.getTenantId();
    return (tenant == null ? "-" : tenant.toString()) + "|" + sessionId;
  }

  public void putResult(String sessionId, OcrFileResult result) {
    SessionEntry entry = sessions.computeIfAbsent(clave(sessionId), id -> new SessionEntry());
    synchronized (entry) {
      entry.touch();
      entry.results.put(result.fileId(), result);
      entry.rebuildConsolidated();
    }
  }

  public List<OcrFileResult> listResults(String sessionId) {
    SessionEntry entry = sessions.get(clave(sessionId));
    if (entry == null || entry.expired(ttl)) {
      return List.of();
    }
    synchronized (entry) {
      entry.touch();
      return new ArrayList<>(entry.results.values());
    }
  }

  public void putConsolidated(String sessionId, String consolidatedContent) {
    SessionEntry entry = sessions.computeIfAbsent(clave(sessionId), id -> new SessionEntry());
    synchronized (entry) {
      entry.touch();
      entry.consolidatedContent =
          consolidatedContent == null ? "" : consolidatedContent;
    }
  }

  public String getConsolidated(String sessionId) {
    SessionEntry entry = sessions.get(clave(sessionId));
    if (entry == null || entry.expired(ttl)) {
      return null;
    }
    synchronized (entry) {
      entry.touch();
      return entry.consolidatedContent;
    }
  }

  public void clear(String sessionId) {
    sessions.remove(clave(sessionId));
  }

  @Scheduled(fixedDelayString = "${lexia.ocr.cache-cleanup-ms:300000}")
  public void evictExpired() {
    sessions.entrySet().removeIf(e -> e.getValue().expired(ttl));
  }

  /** Resultado por archivo en caché de sesión. */
  public record OcrFileResult(
      String fileId,
      String fileName,
      String tipoDocumento,
      boolean legible,
      String motivo,
      double scoreConfianza,
      String textoExtraido) {}

  private static final class SessionEntry {
    private final Map<String, OcrFileResult> results = new LinkedHashMap<>();
    private String consolidatedContent = "";
    private Instant lastAccess = Instant.now();

    void touch() {
      lastAccess = Instant.now();
    }

    boolean expired(Duration ttl) {
      return Instant.now().isAfter(lastAccess.plus(ttl));
    }

    void rebuildConsolidated() {
      StringBuilder sb = new StringBuilder();
      for (OcrFileResult r : results.values()) {
        if (!r.legible()) {
          continue;
        }
        String tipo = StringUtils.hasText(r.tipoDocumento()) ? r.tipoDocumento() : "DOCUMENTO";
        sb.append(tipo).append(":\n:\n: ");
        sb.append(r.textoExtraido() == null ? "" : r.textoExtraido().trim());
        sb.append("\n\n");
      }
      consolidatedContent = sb.toString().trim();
    }
  }
}
