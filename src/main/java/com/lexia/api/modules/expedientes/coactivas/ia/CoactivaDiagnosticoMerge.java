package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico.Documento;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Junta el diagnóstico nuevo con el JSON anterior. Los hitos de otros archivos se conservan.
 * Reanalizar el mismo archivo reemplaza solo lo que aportó ese archivo.
 */
public final class CoactivaDiagnosticoMerge {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_ALERTAS = 40;

  private CoactivaDiagnosticoMerge() {}

  public record Vista(List<Map<String, Object>> documentosProcesados, List<Map<String, Object>> hitosAcumulados) {}

  public record Fusion(String json, int porcentaje, String etapa) {}

  public static Vista vista(String json) {
    JsonNode root = leer(json);
    return new Vista(objetos(root, "documentos_procesados"), objetos(root, "hitos_acumulados"));
  }

  public static Fusion fusionar(
      String previoJson,
      UUID previoArchivoId,
      Instant previoFecha,
      UUID archivoId,
      String nombreArchivo,
      String tipoPieza,
      CoactivaDiagnostico nuevo) {
    List<Documento> docs = nuevo == null ? List.of() : nuevo.documentos();
    return fusionar(
        previoJson,
        previoArchivoId,
        previoFecha,
        archivoId,
        nombreArchivo,
        tipoPieza,
        docs,
        nuevo == null ? List.of() : nuevo.alertas(),
        nuevo == null ? Map.of() : nuevo.datosExtraidos(),
        nuevo == null ? null : nuevo.etapaNormalizada() != null ? nuevo.etapaNormalizada() : nuevo.etapaDetectada(),
        nuevo == null ? null : nuevo.siguienteAccion());
  }

  public static Fusion pieza(
      String previoJson,
      UUID previoArchivoId,
      Instant previoFecha,
      UUID archivoId,
      String nombreArchivo,
      String tipoArchivo,
      String estadoIa,
      String checklistJson) {
    List<String> checklist = textos(leer(checklistJson));
    String tipo = !checklist.isEmpty() ? checklist.get(0) : tipoArchivo;
    List<Documento> docs = new ArrayList<>();
    if ("APROBADO".equals(estadoIa) || "APROBADO_MANUAL".equals(estadoIa)) {
      for (String item : checklist) {
        docs.add(new Documento(item, null, null, true));
      }
    }
    List<String> alertas =
        "RECHAZADO".equals(estadoIa) ? List.of("Pieza rechazada por la IA: " + nombreArchivo) : List.of();
    return fusionar(
        previoJson,
        previoArchivoId,
        previoFecha,
        archivoId,
        nombreArchivo,
        tipo,
        docs,
        alertas,
        Map.of(),
        null,
        null);
  }

  private static Fusion fusionar(
      String previoJson,
      UUID previoArchivoId,
      Instant previoFecha,
      UUID archivoId,
      String nombreArchivo,
      String tipoPieza,
      List<Documento> nuevosDocs,
      List<String> nuevasAlertas,
      Map<String, Object> datosNuevos,
      String etapaNueva,
      String accionNueva) {
    JsonNode previo = leer(previoJson);
    String archivo = archivoId == null ? null : archivoId.toString();

    List<Map<String, Object>> procesados = objetos(previo, "documentos_procesados");
    if (procesados.isEmpty() && previoArchivoId != null && previo != null && previo.size() > 0) {
      procesados.add(pieza(previoArchivoId.toString(), "diagnostico_previo", tipoDe(previo), previoFecha));
    }
    procesados.removeIf(p -> archivo != null && archivo.equals(String.valueOf(p.get("archivo_id"))));
    procesados.add(pieza(archivo, nombreArchivo, tipoPieza, Instant.now()));

    List<Map<String, Object>> hitos = objetos(previo, "hitos_acumulados");
    if (hitos.isEmpty() && previoArchivoId != null) {
      for (Documento doc : documentos(previo)) {
        if (doc.presente()) {
          hitos.add(hito(doc.tipo(), doc.fojaInicio(), previoArchivoId.toString()));
        }
      }
    }
    hitos.removeIf(h -> archivo != null && archivo.equals(String.valueOf(h.get("origen_archivo_id"))));
    for (Documento doc : nuevosDocs) {
      if (doc.presente() && doc.tipo() != null) {
        hitos.add(hito(doc.tipo(), doc.fojaInicio(), archivo));
      }
    }

    List<Documento> unidos = unirDocumentos(documentos(previo), nuevosDocs);
    Map<String, Object> datos = datos(previo);
    if (datosNuevos != null) {
      for (Map.Entry<String, Object> e : datosNuevos.entrySet()) {
        if (util(e.getValue())) {
          datos.put(e.getKey(), e.getValue());
        }
      }
    }
    LinkedHashSet<String> alertas = new LinkedHashSet<>();
    alertas.addAll(textos(previo == null ? null : previo.get("alertas_inconsistencias")));
    if (nuevasAlertas != null) {
      nuevasAlertas.stream().filter(s -> s != null && !s.isBlank()).forEach(alertas::add);
    }
    List<String> alertasLista = alertas.stream().limit(MAX_ALERTAS).toList();

    String etapa = etapaNueva != null && !etapaNueva.isBlank() ? etapaNueva : texto(previo, "etapa_procesal_detectada");
    String accion =
        accionNueva != null && !accionNueva.isBlank() ? accionNueva : texto(previo, "siguiente_accion_sugerida");
    int porcentaje = porcentaje(unidos);

    ObjectNode root = JSON.createObjectNode();
    root.put("porcentaje_completitud", porcentaje);
    if (etapa != null) {
      root.put("etapa_procesal_detectada", etapa);
      root.put("etapa_procesal_actual", etapa);
    }
    root.set("documentos_identificados", JSON.valueToTree(unidos));
    root.set("alertas_inconsistencias", JSON.valueToTree(alertasLista));
    root.set("datos_extraidos", JSON.valueToTree(datos));
    if (accion != null) {
      root.put("siguiente_accion_sugerida", accion);
    }
    root.set("documentos_procesados", JSON.valueToTree(procesados));
    root.set("hitos_acumulados", JSON.valueToTree(hitos));
    try {
      return new Fusion(JSON.writeValueAsString(root), porcentaje, etapa);
    } catch (Exception e) {
      return new Fusion("{}", 0, etapa);
    }
  }

  private static Map<String, Object> pieza(String archivoId, String nombre, String tipo, Instant fecha) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("archivo_id", archivoId);
    row.put("nombre_archivo", nombre);
    row.put("fecha_procesamiento", fecha == null ? null : fecha.toString());
    row.put("tipo_pieza_detectada", tipo);
    return row;
  }

  private static Map<String, Object> hito(String nombre, Integer foja, String origen) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("hito", nombre);
    row.put("foja", foja);
    row.put("fecha_hito", null);
    row.put("origen_archivo_id", origen);
    return row;
  }

  /** Un presente confirmado no se apaga. El documento nuevo solo suma o refuerza. */
  private static List<Documento> unirDocumentos(List<Documento> previos, List<Documento> nuevos) {
    Map<String, Documento> porTipo = new LinkedHashMap<>();
    for (Documento doc : previos) {
      if (doc.tipo() != null) {
        porTipo.put(doc.tipo().toUpperCase(Locale.ROOT), doc);
      }
    }
    for (Documento doc : nuevos) {
      if (doc.tipo() == null) {
        continue;
      }
      String key = doc.tipo().toUpperCase(Locale.ROOT);
      Documento anterior = porTipo.get(key);
      if (anterior == null) {
        porTipo.put(key, doc);
        continue;
      }
      if (!doc.presente()) {
        continue;
      }
      Integer inicio = doc.fojaInicio() != null ? doc.fojaInicio() : anterior.fojaInicio();
      Integer fin = doc.fojaFin() != null ? doc.fojaFin() : anterior.fojaFin();
      porTipo.put(key, new Documento(doc.tipo(), inicio, fin, true));
    }
    return List.copyOf(porTipo.values());
  }

  private static int porcentaje(List<Documento> documentos) {
    if (documentos.isEmpty()) {
      return 0;
    }
    long presentes = documentos.stream().filter(Documento::presente).count();
    return (int) Math.round(100.0 * presentes / documentos.size());
  }

  private static String tipoDe(JsonNode root) {
    for (Documento doc : documentos(root)) {
      if (doc.presente() && doc.tipo() != null) {
        return doc.tipo();
      }
    }
    return null;
  }

  private static List<Documento> documentos(JsonNode root) {
    List<Documento> items = new ArrayList<>();
    if (root == null) {
      return items;
    }
    JsonNode n = root.get("documentos_identificados");
    if (n == null || !n.isArray()) {
      return items;
    }
    for (JsonNode item : n) {
      if (!item.isObject()) {
        continue;
      }
      String tipo = texto(item, "tipo");
      if (tipo == null) {
        continue;
      }
      boolean presente = item.path("presente").asBoolean(false);
      Integer foja = entero(item, "foja_inicio", "fojaInicio");
      Integer fin = entero(item, "foja_fin", "fojaFin");
      items.add(new Documento(tipo, foja, fin, presente));
    }
    return items;
  }

  private static Integer entero(JsonNode item, String... campos) {
    for (String campo : campos) {
      JsonNode n = item.get(campo);
      if (n != null && n.isNumber()) {
        return n.asInt();
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> datos(JsonNode root) {
    Map<String, Object> datos = new LinkedHashMap<>();
    if (root == null) {
      return datos;
    }
    JsonNode n = root.get("datos_extraidos");
    if (n == null || !n.isObject()) {
      return datos;
    }
    return JSON.convertValue(n, Map.class);
  }

  private static List<Map<String, Object>> objetos(JsonNode root, String campo) {
    List<Map<String, Object>> items = new ArrayList<>();
    if (root == null) {
      return items;
    }
    JsonNode n = root.get(campo);
    if (n == null || !n.isArray()) {
      return items;
    }
    for (JsonNode item : n) {
      if (item.isObject()) {
        items.add(JSON.convertValue(item, Map.class));
      }
    }
    return items;
  }

  private static List<String> textos(JsonNode n) {
    List<String> items = new ArrayList<>();
    if (n == null || !n.isArray()) {
      return items;
    }
    for (JsonNode item : n) {
      if (item.isTextual() && !item.asText().isBlank()) {
        items.add(item.asText().trim());
      }
    }
    return items;
  }

  private static String texto(JsonNode root, String campo) {
    if (root == null) {
      return null;
    }
    JsonNode n = root.get(campo);
    if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
      return null;
    }
    return n.asText().trim();
  }

  private static boolean util(Object v) {
    if (v == null) {
      return false;
    }
    if (v instanceof String s) {
      return !s.isBlank();
    }
    if (v instanceof List<?> list) {
      return !list.isEmpty();
    }
    if (v instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    return true;
  }

  private static JsonNode leer(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(json);
      return node.isObject() || node.isArray() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

}
