package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;

/** JSON del extractor ({@code acto} + {@code variables}) → {@link MinutaViviendaData}. */
public final class MinutaVariableBinder {

  private MinutaVariableBinder() {}

  public static MinutaViviendaData bind(JsonNode root, ObjectMapper mapper) {
    if (root == null || root.isNull() || !root.isObject()) {
      return new MinutaViviendaData();
    }
    JsonNode vars = root.get("variables");
    JsonNode source = vars != null && vars.isObject() ? vars : root;
    ObjectNode canon = mapper.createObjectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if ("acto".equals(entry.getKey()) || "variables".equals(entry.getKey())) {
        continue;
      }
      String campo = MinutaTagAliases.canonico(entry.getKey());
      JsonNode value = entry.getValue();
      if (value == null || value.isNull()) {
        continue;
      }
      String text = value.isTextual() ? value.asText() : value.asText("");
      if (text.isBlank() || "null".equalsIgnoreCase(text) || "nodata".equalsIgnoreCase(text)) {
        continue;
      }
      if (canon.has(campo) && !canon.get(campo).asText("").isBlank()) {
        continue;
      }
      canon.set(campo, TextNode.valueOf(text.trim()));
    }
    return mapper.convertValue(canon, MinutaViviendaData.class);
  }
}
