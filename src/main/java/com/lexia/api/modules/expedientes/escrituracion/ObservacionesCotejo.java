package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ObservacionCotejoItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reemplaza observaciones abiertas del cotejo. La clave es código + mensaje. */
final class ObservacionesCotejo {

  private static final Pattern DETALLE =
      Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s+([^:]+):\\s*(.*)$", Pattern.CASE_INSENSITIVE);

  private ObservacionesCotejo() {}

  static void reemplazar(
      TitleObservationRepository repo, UUID tenantId, UUID studyId, List<Entrada> nuevas) {
    List<TitleObservation> actuales =
        repo.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(studyId, tenantId);
    Set<String> resueltas = new LinkedHashSet<>();
    for (TitleObservation row : actuales) {
      Parsed parsed = parse(row.getDetail());
      if ("RESOLVED".equals(row.getStatus())) {
        resueltas.add(clave(parsed.code(), parsed.mensaje()));
      } else {
        repo.delete(row);
      }
    }
    repo.flush();
    Set<String> vistas = new LinkedHashSet<>();
    for (Entrada entrada : nuevas) {
      if (entrada == null || entrada.mensaje() == null || entrada.mensaje().isBlank()) {
        continue;
      }
      String key = clave(entrada.code(), entrada.mensaje());
      if (!vistas.add(key) || resueltas.contains(key)) {
        continue;
      }
      repo.save(TitleObservation.create(tenantId, studyId, formato(entrada)));
    }
  }

  static List<ObservacionCotejoItem> pendientes(
      TitleObservationRepository repo, UUID tenantId, UUID studyId) {
    Map<String, ObservacionCotejoItem> unicas = new LinkedHashMap<>();
    for (TitleObservation row : repo.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(studyId, tenantId)) {
      if ("RESOLVED".equals(row.getStatus())) {
        continue;
      }
      ObservacionCotejoItem item = aItem(row);
      unicas.putIfAbsent(clave(item.code(), item.mensaje()), item);
    }
    List<ObservacionCotejoItem> items = new ArrayList<>(unicas.values());
    items.sort((a, b) -> Integer.compare(orden(a.severidad()), orden(b.severidad())));
    return List.copyOf(items);
  }

  static List<Entrada> desdeTextos(List<String> textos) {
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

  private static ObservacionCotejoItem aItem(TitleObservation row) {
    Parsed parsed = parse(row.getDetail());
    return new ObservacionCotejoItem(
        row.getId(),
        parsed.code(),
        parsed.severidad(),
        parsed.mensaje(),
        "PENDIENTE",
        row.getCreatedAt() == null ? Instant.now() : row.getCreatedAt());
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

  static String clave(String code, String mensaje) {
    String c = code == null ? "OBS" : code.trim().toUpperCase(Locale.ROOT);
    String m = mensaje == null ? "" : mensaje.trim().toLowerCase(Locale.ROOT);
    return c + "|" + m;
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

  record Entrada(String code, String severidad, String mensaje) {}

  private record Parsed(String code, String severidad, String mensaje) {}
}
