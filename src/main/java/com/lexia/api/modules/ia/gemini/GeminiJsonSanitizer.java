package com.lexia.api.modules.ia.gemini;

import org.springframework.util.StringUtils;

/** Limpia respuestas Gemini con fences Markdown antes de ObjectMapper. */
public final class GeminiJsonSanitizer {

  private GeminiJsonSanitizer() {}

  public static String limpiar(String rawResponse) {
    if (!StringUtils.hasText(rawResponse)) {
      return "{}";
    }

    String json = rawResponse.trim();
    // BOM / rare wrappers
    if (json.startsWith("\uFEFF")) {
      json = json.substring(1).trim();
    }

    if (json.regionMatches(true, 0, "```json", 0, 7)) {
      json = json.substring(7);
    } else if (json.startsWith("```")) {
      json = json.substring(3);
    }

    json = json.trim();
    if (json.endsWith("```")) {
      json = json.substring(0, json.length() - 3);
    }

    json = json.trim();
    // Prefijo "json" suelto tras quitar fence
    if (json.regionMatches(true, 0, "json", 0, 4)
        && (json.length() == 4 || Character.isWhitespace(json.charAt(4)))) {
      json = json.substring(4).trim();
    }

    int start = json.indexOf('{');
    int end = json.lastIndexOf('}');
    if (start >= 0 && end > start) {
      json = json.substring(start, end + 1);
    }

    return json.trim();
  }
}
