package com.lexia.api.modules.expedientes.escrituracion.core;

import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reemplaza observaciones abiertas del cotejo. La clave es documento + código + mensaje: la misma
 * observación sobre dos documentos son dos filas; sin documento es una observación general.
 */
public final class ObservacionesCotejo {

  private static final Logger LOG = LoggerFactory.getLogger(ObservacionesCotejo.class);
  private static final Pattern DETALLE =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$", Pattern.CASE_INSENSITIVE);

  private ObservacionesCotejo() {}

  /** Sin documentos válidos: todas quedan generales (p. ej. observaciones en texto del abogado). */
  public static void reemplazar(
      TitleObservationRepository repo, UUID tenantId, UUID studyId, List<Entrada> nuevas) {
    reemplazar(repo, tenantId, studyId, nuevas, Set.of());
  }

  /**
   * @param documentosDelExpediente ids de los {@code LegalDocument} vigentes del expediente. Un
   *     {@code documentoId} fuera de este conjunto ({@code consolidado}, {@code DOC-xxxx}, otro
   *     expediente, inventado) se guarda como null, salvo que la misma observación ya estuviera
   *     abierta ligada a un documento: entonces conserva ese documento en vez de duplicarse como
   *     general.
   */
  public static void reemplazar(
      TitleObservationRepository repo,
      UUID tenantId,
      UUID studyId,
      List<Entrada> nuevas,
      Set<UUID> documentosDelExpediente) {
    List<TitleObservation> actuales =
        repo.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(studyId, tenantId);
    Set<String> resueltas = new LinkedHashSet<>();
    // codigo|mensaje → documentos donde esa observación estaba abierta (antes de borrarlas).
    Map<String, Set<UUID>> abiertasConDocumento = new LinkedHashMap<>();
    for (TitleObservation row : actuales) {
      Parsed parsed = parse(row.getDetail());
      if ("RESOLVED".equals(row.getStatus())) {
        resueltas.add(clave(texto(row.getLegalDocumentId()), parsed.code(), parsed.mensaje()));
      } else {
        UUID documento = row.getLegalDocumentId();
        if (documento != null && documentosDelExpediente.contains(documento)) {
          abiertasConDocumento
              .computeIfAbsent(clave(null, parsed.code(), parsed.mensaje()), k -> new LinkedHashSet<>())
              .add(documento);
        }
        repo.delete(row);
      }
    }
    repo.flush();
    Set<String> vistas = new LinkedHashSet<>();
    int descartados = 0;
    for (Entrada entrada : nuevas) {
      if (entrada == null || entrada.mensaje() == null || entrada.mensaje().isBlank()) {
        continue;
      }
      UUID documento = documentoValido(entrada.documentoId(), documentosDelExpediente);
      if (documento == null && entrada.documentoId() != null && !entrada.documentoId().isBlank()) {
        descartados++;
      }
      Set<UUID> documentos =
          documento != null
              ? Set.of(documento)
              : abiertasConDocumento.getOrDefault(
                  clave(null, entrada.code(), entrada.mensaje()), Collections.singleton(null));
      for (UUID destino : documentos) {
        String key = clave(texto(destino), entrada.code(), entrada.mensaje());
        if (!vistas.add(key) || resueltas.contains(key)) {
          continue;
        }
        repo.save(TitleObservation.create(tenantId, studyId, formato(entrada), destino));
      }
    }
    if (descartados > 0) {
      // Sin ids ni mensajes: el id lo propuso el LLM y el mensaje puede traer datos personales.
      LOG.warn(
          "Cotejo study={}: {} observaciones con documentoId ajeno al expediente se guardan como generales",
          studyId,
          descartados);
    }
  }

  public static List<ObservacionCotejoItem> pendientes(
      TitleObservationRepository repo, UUID tenantId, UUID studyId) {
    Map<String, ObservacionCotejoItem> unicas = new LinkedHashMap<>();
    for (TitleObservation row : repo.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(studyId, tenantId)) {
      if ("RESOLVED".equals(row.getStatus())) {
        continue;
      }
      ObservacionCotejoItem item = aItem(row);
      unicas.putIfAbsent(clave(texto(item.documentoId()), item.code(), item.mensaje()), item);
    }
    List<ObservacionCotejoItem> items = new ArrayList<>(unicas.values());
    items.sort((a, b) -> Integer.compare(orden(a.severidad()), orden(b.severidad())));
    return List.copyOf(items);
  }

  /** Observaciones en texto ({@code POST estudio-titulo}): no traen documento, quedan generales. */
  public static List<Entrada> desdeTextos(List<String> textos) {
    List<Entrada> entradas = new ArrayList<>();
    if (textos == null) {
      return entradas;
    }
    for (String texto : textos) {
      Parsed parsed = parse(texto);
      if (!parsed.mensaje().isBlank()) {
        entradas.add(new Entrada(parsed.code(), parsed.severidad(), parsed.mensaje()));
      }
    }
    return entradas;
  }

  static String formato(Entrada entrada) {
    String severidad = normalizarSeveridad(entrada.severidad());
    String code = entrada.code() == null || entrada.code().isBlank() ? "OBS" : entrada.code().trim();
    return "[" + severidad + "] " + code + ": " + entrada.mensaje().trim();
  }

  static UUID documentoValido(String raw, Set<UUID> documentosDelExpediente) {
    if (raw == null || raw.isBlank() || documentosDelExpediente == null) {
      return null;
    }
    try {
      UUID id = UUID.fromString(raw.trim());
      return documentosDelExpediente.contains(id) ? id : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static ObservacionCotejoItem aItem(TitleObservation row) {
    Parsed parsed = parse(row.getDetail());
    return new ObservacionCotejoItem(
        row.getId(),
        parsed.code(),
        parsed.severidad(),
        parsed.mensaje(),
        "PENDIENTE",
        row.getCreatedAt() == null ? Instant.now() : row.getCreatedAt(),
        row.getLegalDocumentId());
  }

  private static Parsed parse(String detail) {
    String raw = detail == null ? "" : detail.trim();
    Matcher matcher = DETALLE.matcher(raw);
    if (matcher.matches()) {
      return new Parsed(matcher.group(2).trim(), normalizarSeveridad(matcher.group(1)), matcher.group(3).trim());
    }
    return new Parsed("OBS", "MEDIUM", raw);
  }

  private static int orden(String severidad) {
    return switch (severidad) {
      case "HIGH" -> 0;
      case "LOW" -> 2;
      default -> 1;
    };
  }

  /** {@code documentoId|codigo|mensaje}; documento vacío = observación general. */
  static String clave(String documentoId, String code, String mensaje) {
    String d = documentoId == null ? "" : documentoId.trim().toLowerCase(Locale.ROOT);
    String c = code == null ? "OBS" : code.trim().toUpperCase(Locale.ROOT);
    String m = mensaje == null ? "" : mensaje.trim().toLowerCase(Locale.ROOT);
    return d + "|" + c + "|" + m;
  }

  private static String texto(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String normalizarSeveridad(String raw) {
    String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if ("HIGH".equals(value) || "ALTA".equals(value)) {
      return "HIGH";
    }
    if ("LOW".equals(value) || "BAJA".equals(value)) {
      return "LOW";
    }
    return "MEDIUM";
  }

  /** @param documentoId id propuesto (sin validar) o null si es general. */
  public record Entrada(String code, String severidad, String mensaje, String documentoId) {

    public Entrada(String code, String severidad, String mensaje) {
      this(code, severidad, mensaje, null);
    }
  }

  private record Parsed(String code, String severidad, String mensaje) {}
}
