package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.ia.gemini.GeminiJsonSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Parsea la respuesta JSON del LLM al contrato
 *
 * <pre>
 * {"estado": "APROBADO|RECHAZADO", "confianza": 95,
 *  "razon_rechazo": "...", "checklist_cumplido": ["..."]}
 * </pre>
 *
 * Tolerante a fences Markdown, alias en inglés y confianza en escala 0..1. Nunca lanza: ante
 * una respuesta inválida devuelve {@code ERROR} con el diagnóstico.
 */
@Component
public final class CoactivaIaResultadoParser {

  /** Contrato que se adjunta al system prompt del catálogo. */
  public static final String FORMATO_JSON =
      """

      Responde ÚNICAMENTE con un objeto JSON válido, sin markdown ni texto adicional:
      {"estado": "APROBADO" | "RECHAZADO",
       "confianza": 0-100,
       "razon_rechazo": "motivo concreto si RECHAZADO, cadena vacía si APROBADO",
       "checklist_cumplido": ["item_verificado_1", "item_verificado_2"]}
      Usa RECHAZADO ante cualquier duda razonable; no inventes datos que no estén en el texto.
      """;

  private static final Set<String> APROBADO =
      Set.of("APROBADO", "APPROVED", "VALIDO", "VÁLIDO", "VALID", "OK", "ACEPTADO");
  private static final Set<String> RECHAZADO =
      Set.of("RECHAZADO", "REJECTED", "INVALIDO", "INVÁLIDO", "INVALID", "NO_VALIDO");
  private static final String SIN_MOTIVO = "La IA rechazó el documento sin indicar el motivo.";
  private static final int MAX_MOTIVO = 2000;

  private final ObjectMapper objectMapper;

  public CoactivaIaResultadoParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CoactivaIaResultado parse(String raw) {
    if (!StringUtils.hasText(raw)) {
      return CoactivaIaResultado.error("La IA devolvió una respuesta vacía.");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(GeminiJsonSanitizer.limpiar(raw));
    } catch (Exception e) {
      return CoactivaIaResultado.error("Respuesta de la IA no es JSON válido: " + truncate(raw, 160));
    }
    if (root == null || !root.isObject()) {
      return CoactivaIaResultado.error("Respuesta de la IA no es un objeto JSON.");
    }
    return parse(root);
  }

  public CoactivaIaResultado parse(JsonNode root) {
    if (root == null || !root.isObject()) {
      return CoactivaIaResultado.error("Respuesta de la IA no es un objeto JSON.");
    }
    String estado = texto(root, "estado", "status", "resultado", "valido");
    Integer confianza = confianza(root);
    String razon = texto(root, "razon_rechazo", "razonRechazo", "motivo_rechazo", "motivo", "razon", "reason");
    List<String> checklist = checklist(root);

    if (estado == null) {
      // Compatibilidad con {"valido": true/false}
      JsonNode valido = root.path("valido");
      if (valido.isBoolean()) {
        estado = valido.asBoolean() ? "APROBADO" : "RECHAZADO";
      }
    }
    if (estado == null) {
      return CoactivaIaResultado.error("La IA no indicó el campo 'estado'.");
    }
    String normalizado = estado.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    if ("TRUE".equals(normalizado)) {
      normalizado = "APROBADO";
    } else if ("FALSE".equals(normalizado)) {
      normalizado = "RECHAZADO";
    }
    if (APROBADO.contains(normalizado)) {
      return CoactivaIaResultado.aprobado(confianza, checklist);
    }
    if (RECHAZADO.contains(normalizado)) {
      return CoactivaIaResultado.rechazado(
          confianza, StringUtils.hasText(razon) ? truncate(razon.trim(), MAX_MOTIVO) : SIN_MOTIVO, checklist);
    }
    return CoactivaIaResultado.error("Estado desconocido devuelto por la IA: " + truncate(estado, 60));
  }

  /** Serializa el checklist como JSON array para persistirlo en texto. */
  public String checklistJson(List<String> checklist) {
    try {
      return objectMapper.writeValueAsString(checklist == null ? List.of() : checklist);
    } catch (Exception e) {
      return "[]";
    }
  }

  private static String texto(JsonNode root, String... keys) {
    for (String key : keys) {
      JsonNode n = root.get(key);
      if (n != null && !n.isNull() && !n.isContainerNode()) {
        String v = n.asText();
        if (StringUtils.hasText(v)) {
          return v;
        }
      }
    }
    return null;
  }

  private static Integer confianza(JsonNode root) {
    JsonNode n = root.get("confianza");
    if (n == null) {
      n = root.get("confidence");
    }
    if (n == null || n.isNull()) {
      return null;
    }
    double v;
    if (n.isNumber()) {
      v = n.asDouble();
    } else {
      try {
        v = Double.parseDouble(n.asText().replace("%", "").replace(',', '.').trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (v >= 0 && v <= 1) {
      v = v * 100;
    }
    long redondeado = Math.round(v);
    return (int) Math.max(0, Math.min(100, redondeado));
  }

  private static List<String> checklist(JsonNode root) {
    JsonNode n = root.get("checklist_cumplido");
    if (n == null) {
      n = root.get("checklistCumplido");
    }
    if (n == null) {
      n = root.get("checklist");
    }
    List<String> items = new ArrayList<>();
    if (n == null || !n.isArray()) {
      return items;
    }
    for (JsonNode item : n) {
      String v = item.isContainerNode() ? item.toString() : item.asText();
      if (StringUtils.hasText(v)) {
        items.add(v.trim());
      }
    }
    return items;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
