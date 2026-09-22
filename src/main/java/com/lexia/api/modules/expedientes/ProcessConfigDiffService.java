package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.ProcessConfigDiffLine;
import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.ProcessConfigDiffView;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class ProcessConfigDiffService {

  private final ObjectMapper objectMapper;

  public ProcessConfigDiffService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ProcessConfigDiffView diff(
      int fromVersion, int toVersion, String fromJson, String toJson) {
    if (fromJson == null || fromJson.isBlank() || toJson == null || toJson.isBlank()) {
      return new ProcessConfigDiffView(
          fromVersion,
          toVersion,
          false,
          "Una o ambas versiones no tienen snapshot almacenado (publicaciones anteriores a R2).",
          List.of());
    }
    try {
      JsonNode fromNode = objectMapper.readTree(fromJson);
      JsonNode toNode = objectMapper.readTree(toJson);
      Map<String, String> left = flatten("", fromNode);
      Map<String, String> right = flatten("", toNode);
      List<ProcessConfigDiffLine> lines = compare(left, right);
      return new ProcessConfigDiffView(fromVersion, toVersion, true, null, lines);
    } catch (Exception ex) {
      return new ProcessConfigDiffView(
          fromVersion,
          toVersion,
          false,
          "No se pudo calcular el diff: " + ex.getMessage(),
          List.of());
    }
  }

  private static List<ProcessConfigDiffLine> compare(
      Map<String, String> left, Map<String, String> right) {
    List<ProcessConfigDiffLine> lines = new ArrayList<>();
    TreeMap<String, String> allKeys = new TreeMap<>();
    allKeys.putAll(left);
    allKeys.putAll(right);
    for (String key : allKeys.keySet()) {
      String before = left.get(key);
      String after = right.get(key);
      if (before == null && after != null) {
        lines.add(new ProcessConfigDiffLine(key, "ADDED", null, after));
      } else if (before != null && after == null) {
        lines.add(new ProcessConfigDiffLine(key, "REMOVED", before, null));
      } else if (before != null && !before.equals(after)) {
        lines.add(new ProcessConfigDiffLine(key, "CHANGED", before, after));
      }
    }
    return lines;
  }

  private static Map<String, String> flatten(String prefix, JsonNode node) {
    Map<String, String> out = new LinkedHashMap<>();
    if (node == null || node.isNull()) {
      return out;
    }
    if (node.isValueNode()) {
      out.put(prefix.isEmpty() ? "/" : prefix, node.asText());
      return out;
    }
    if (node.isArray()) {
      int index = 0;
      for (JsonNode child : node) {
        String path = prefix + "[" + index + "]";
        out.putAll(flatten(path, child));
        index++;
      }
      return out;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String path = prefix.isEmpty() ? "/" + entry.getKey() : prefix + "/" + entry.getKey();
      out.putAll(flatten(path, entry.getValue()));
    }
    return out;
  }
}
