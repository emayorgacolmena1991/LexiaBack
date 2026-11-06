package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.ejd.EjdRuleBodyParser.ParsedRuleBody;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Motor determinístico LEXIA-10 (subset EJD V1–V6). La evidencia la lee un abogado: solo nombra
 * campos y documentos que existen en {@code extracted_data}.
 */
@Component
public class LexiaRulesEngine {

  /** Formato de DocumentosExtraidosStore: {@code NNN::documentoId::clave}. */
  private static final String SEP = "::";

  private static final Map<String, String> NOMBRES_CAMPO =
      Map.of("MATRICULA", "Número de matrícula", "FOLIO", "Folio");
  private static final Pattern SEVERIDAD = Pattern.compile("^\\[(HIGH|MEDIUM|LOW)]\\s*(.*)$");

  public EvaluationOutcome evaluateInternalConsistency(
      List<ExtractedData> rows, RuleDef rule, ParsedRuleBody body) {
    if (rows.isEmpty()) {
      return EvaluationOutcome.pending(
          "Aún no hay datos extraídos: ejecute el análisis de documentos para revisar la consistencia interna.");
    }
    Map<String, Map<String, List<String>>> valoresPorDocumento = new LinkedHashMap<>();
    for (Campo campo : campos(rows)) {
      if (campo.documento() != null) {
        valoresPorDocumento
            .computeIfAbsent(campo.documento(), k -> new LinkedHashMap<>())
            .computeIfAbsent(normalizeKey(campo.clave()), k -> new ArrayList<>())
            .add(campo.valor());
      }
    }
    List<String> conflictos = new ArrayList<>();
    int comparados = 0;
    for (var documento : valoresPorDocumento.entrySet()) {
      for (var campo : documento.getValue().entrySet()) {
        if (campo.getValue().size() < 2) {
          continue;
        }
        comparados++;
        Map<String, String> distintos = new LinkedHashMap<>();
        campo.getValue().forEach(valor -> distintos.putIfAbsent(normalizeValue(valor), valor));
        if (distintos.size() > 1) {
          conflictos.add(
              "En '%s', el campo '%s' tiene valores distintos: %s."
                  .formatted(
                      documento.getKey(),
                      nombreCampo(campo.getKey()),
                      citar(new LinkedHashSet<>(distintos.values()))));
        }
      }
    }
    if (!conflictos.isEmpty()) {
      return EvaluationOutcome.fail(String.join(" ", conflictos));
    }
    // Sin un campo repetido dentro de un documento no hubo comparación: aprobar sería falso.
    if (comparados == 0) {
      return EvaluationOutcome.pending(
          valoresPorDocumento.isEmpty()
              ? "No se evaluó: no hay campos extraídos por documento que comparar."
              : "No se evaluó: en %d documento(s) revisado(s) ningún campo aparece más de una vez, así que no hay valores que comparar."
                  .formatted(valoresPorDocumento.size()));
    }
    return EvaluationOutcome.pass(
        "Sin contradicciones internas: %d campo(s) comparado(s) en %d documento(s)."
            .formatted(comparados, valoresPorDocumento.size()));
  }

  public EvaluationOutcome evaluateCrossDocument(
      List<ExtractedData> rows, RuleDef rule, ParsedRuleBody body) {
    if (rows.isEmpty()) {
      return EvaluationOutcome.pending(
          "Aún no hay datos extraídos para comparar entre documentos.");
    }
    List<Campo> campos = campos(rows);
    List<String> diferencias = new ArrayList<>();
    List<String> coincidencias = new ArrayList<>();
    List<String> ausentes = new ArrayList<>();
    for (String key : body.keys()) {
      String nombre = nombreCampo(key);
      Map<String, Set<String>> documentosPorValor = new LinkedHashMap<>();
      Map<String, String> valorOriginal = new HashMap<>();
      for (Campo campo : campos) {
        if (normalizeKey(campo.clave()).contains(key)) {
          String normalizado = normalizeValue(campo.valor());
          valorOriginal.putIfAbsent(normalizado, campo.valor());
          Set<String> documentos =
              documentosPorValor.computeIfAbsent(normalizado, k -> new LinkedHashSet<>());
          if (campo.documento() != null) {
            documentos.add(campo.documento());
          }
        }
      }
      if (documentosPorValor.isEmpty()) {
        ausentes.add(nombre);
      } else if (documentosPorValor.size() > 1) {
        List<String> detalle = new ArrayList<>();
        documentosPorValor.forEach(
            (valor, documentos) ->
                detalle.add(
                    "'%s'%s".formatted(valorOriginal.get(valor), enDocumentos(documentos))));
        diferencias.add(
            "'%s' no coincide entre documentos: %s.".formatted(nombre, String.join("; ", detalle)));
      } else {
        Set<String> documentos = documentosPorValor.values().iterator().next();
        coincidencias.add(
            documentos.size() > 1
                ? "'%s' coincide%s.".formatted(nombre, enDocumentos(documentos))
                : "'%s' solo consta%s; no hay otro documento con el cual cotejarlo."
                    .formatted(nombre, enDocumentos(documentos)));
      }
    }
    if (!diferencias.isEmpty()) {
      return EvaluationOutcome.fail(String.join(" ", diferencias));
    }
    String faltan =
        ausentes.isEmpty()
            ? ""
            : "No se encontró %s en %s."
                .formatted(
                    String.join(" ni ", ausentes.stream().map(n -> "'" + n + "'").toList()),
                    documentosAnalizados(campos));
    if (coincidencias.isEmpty()) {
      return EvaluationOutcome.observation(
          faltan + " Verifique que conste en ellos o complete el dato manualmente.");
    }
    return EvaluationOutcome.pass((String.join(" ", coincidencias) + " " + faltan).trim());
  }

  /**
   * V6 nunca aprueba sola: la decisión es del abogado.
   *
   * @param observaciones abiertas del estudio de título; {@code null} si aún no hay dictamen
   */
  public EvaluationOutcome evaluateHumanLegalReview(RuleDef rule, List<String> observaciones) {
    if (observaciones == null) {
      return EvaluationOutcome.pending(
          "Aún no hay dictamen de IA del estudio de título; la evaluación jurídica corresponde al abogado.");
    }
    if (observaciones.isEmpty()) {
      return EvaluationOutcome.pending(
          "La IA no reportó observaciones abiertas en el estudio de título; la decisión final corresponde al abogado.");
    }
    return EvaluationOutcome.pending(
        "La IA detectó %d observación(es) en el estudio de título: %s. Confirme cada una; la decisión final corresponde al abogado."
            .formatted(
                observaciones.size(),
                String.join(
                    "; ", observaciones.stream().map(LexiaRulesEngine::observacionLegible).toList())));
  }

  /** Nombre legible de un código técnico: {@code HISTORIA_DOMINIO} → {@code Historia dominio}. */
  public static String nombreLegible(String codigo) {
    String texto =
        codigo.trim().replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[_.\\-]+", " ").toLowerCase(Locale.ROOT);
    return texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
  }

  private static String observacionLegible(String detalle) {
    Matcher matcher = SEVERIDAD.matcher(detalle == null ? "" : detalle.trim());
    if (!matcher.matches()) {
      return detalle;
    }
    String severidad =
        switch (matcher.group(1)) {
          case "HIGH" -> "alta";
          case "MEDIUM" -> "media";
          default -> "baja";
        };
    return "(%s) %s".formatted(severidad, matcher.group(2));
  }

  private static List<Campo> campos(List<ExtractedData> rows) {
    Map<String, String> documentos = new HashMap<>();
    for (ExtractedData row : rows) {
      String[] partes = partes(row);
      if (partes.length == 3 && !isBlank(row.getFieldValue())) {
        if ("@nombre".equals(partes[2])) {
          documentos.put(partes[0] + SEP + partes[1], row.getFieldValue().trim());
        } else if ("@tipo".equals(partes[2])) {
          documentos.putIfAbsent(partes[0] + SEP + partes[1], nombreLegible(row.getFieldValue()));
        }
      }
    }
    List<Campo> campos = new ArrayList<>();
    for (ExtractedData row : rows) {
      if (isBlank(row.getFieldLabel()) || isBlank(row.getFieldValue())) {
        continue;
      }
      String[] partes = partes(row);
      String valor = row.getFieldValue().trim();
      if (partes.length != 3) {
        String documento = row.getDocumentId() == null ? null : row.getDocumentId().toString();
        campos.add(new Campo(documento, row.getFieldLabel().trim(), valor));
      } else if (!partes[2].startsWith("@")) {
        String prefijo = partes[0] + SEP + partes[1];
        campos.add(new Campo(documentos.getOrDefault(prefijo, partes[1]), partes[2], valor));
      }
    }
    return campos;
  }

  private static String[] partes(ExtractedData row) {
    return row.getFieldLabel() == null ? new String[0] : row.getFieldLabel().split(SEP, 3);
  }

  private static String documentosAnalizados(List<Campo> campos) {
    Set<String> documentos = new LinkedHashSet<>();
    for (Campo campo : campos) {
      if (campo.documento() != null) {
        documentos.add(campo.documento());
      }
    }
    return documentos.isEmpty()
        ? "los datos extraídos"
        : "los documentos analizados (%s)".formatted(String.join(", ", documentos));
  }

  private static String enDocumentos(Set<String> documentos) {
    return documentos.isEmpty()
        ? ""
        : " en "
            + String.join(", ", documentos.stream().map(documento -> "'" + documento + "'").toList());
  }

  private static String citar(Set<String> valores) {
    return String.join(" y ", valores.stream().map(valor -> "'" + valor + "'").toList());
  }

  private static String nombreCampo(String clave) {
    return NOMBRES_CAMPO.getOrDefault(normalizeKey(clave), nombreLegible(clave));
  }

  private static String normalizeKey(String raw) {
    String sinAcentos = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return sinAcentos.toUpperCase(Locale.ROOT).replace(' ', '_');
  }

  private static String normalizeValue(String raw) {
    return raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record Campo(String documento, String clave, String valor) {}

  public record EvaluationOutcome(String result, String evidence) {
    static EvaluationOutcome pass(String evidence) {
      return new EvaluationOutcome("PASS", evidence);
    }

    static EvaluationOutcome fail(String evidence) {
      return new EvaluationOutcome("FAIL", evidence);
    }

    static EvaluationOutcome pending(String evidence) {
      return new EvaluationOutcome("PENDING", evidence);
    }

    static EvaluationOutcome observation(String evidence) {
      return new EvaluationOutcome("OBSERVATION", evidence);
    }
  }
}
