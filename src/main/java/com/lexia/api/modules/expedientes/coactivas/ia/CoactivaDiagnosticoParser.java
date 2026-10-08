package com.lexia.api.modules.expedientes.coactivas.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico.Documento;
import com.lexia.api.modules.ia.gemini.GeminiJsonSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Normaliza el JSON de diagnóstico del expediente unificado. No lanza. */
@Component
public final class CoactivaDiagnosticoParser {

  public static final String FORMATO_JSON =
      """

      Responde ÚNICAMENTE con un objeto JSON válido, sin markdown ni texto adicional:
      {"porcentaje_completitud": 0,
       "etapa_procesal_detectada": "PREVIA|RPV|OPI_EMITIDA|NOTIFICACION_COA|MEDIDAS_CAUTELARES|ESCRITO|EMBARGO|HONORARIOS|AVALUO|REMATE|CONVENIO|ARCHIVADO",
       "documentos_identificados": [{"tipo": "PAGARE", "foja_inicio": 1, "foja_fin": 4, "presente": true}],
       "alertas_inconsistencias": ["motivo concreto"],
       "datos_extraidos": {
         "juicio": null, "operacion": null, "deudor": null, "monto_mora": null,
         "cedula_deudor_principal": null, "correo_notificacion_deudor": null,
         "nombre_garante_solidario": null, "cedula_garante_solidario": null,
         "nombre_depositario_judicial": null, "cedula_depositario_judicial": null,
         "nombre_funcionario_coactiva": null, "nombre_gerente_general": null, "nombre_abogado_secretario": null,
         "numero_resolucion_delegacion": null, "fecha_resolucion_delegacion": null,
         "monto_deuda_total": null, "monto_honorarios": null, "cuenta_honorarios_abogado": null,
         "banco_embargado": null,
         "cuentas_embargadas": [{"numero_cuenta": null, "tipo": "CORRIENTE|AHORROS", "titular": "DEUDOR|GARANTE", "banco": null, "monto_retenido": null}],
         "correo_estudio_juridico_externo": null, "correo_funcionario_coactiva": null, "correo_coactiva_institucional": null,
         "nombre_deudor_principal_2": null, "cedula_deudor_principal_2": null,
         "nombre_garante_solidario_2": null, "cedula_garante_solidario_2": null,
         "fecha_liquidacion": null, "fecha_embargo": null, "fecha_orden_pago_inmediato": null,
         "fecha_escrito_presentado": null, "fecha_comprobante_pago": null, "numero_comprobante_pago": null,
         "monto_retencion": null, "monto_credito_original": null, "monto_abono": null,
         "monto_retencion_1": null, "monto_retencion_2": null,
         "numero_oficio": null, "institucion_financiera": null,
         "nombre_embargado": null, "cedula_embargado": null, "calidad_embargado": null,
         "nombre_coactivado": null, "cedula_coactivado": null, "calidad_coactivado": null,
         "numero_cuenta_retencion": null, "numero_cuenta_destino": null, "tipo_cuenta_destino": null,
         "numero_oficio_retencion_1": null, "numero_oficio_retencion_2": null,
         "correo_notificacion_2": null, "correo_notificacion_3": null,
         "numero_cuotas": null, "fecha_convenio": null,
         "nombre_receptor": null, "cedula_receptor": null,
         "titulo_delegado": null, "titulo_secretario": null, "titulo_gerente_general": null, "titulo_depositario": null},
       "siguiente_accion_sugerida": "qué debe hacer el abogado"}
      No inventes documentos ni fojas que no estén en el texto. Si falta, presente=false y sin fojas.
      En datos_extraidos copia solo valores que consten literalmente en el texto; si un dato no aparece usa null
      (cuentas_embargadas: [] si no hay oficios de retención). No deduzcas, completes ni estimes valores.
      Nombres sin títulos ni tratamientos (sin "señor", "Sr.", "Abg.", "Mgs.", "Ing."). Cédulas solo con dígitos.
      Montos como número decimal sin símbolo ni separador de miles. Fechas tal como constan en el documento.
      Correos solo si aparecen escritos en el expediente.
      """;

  private final ObjectMapper objectMapper;

  public CoactivaDiagnosticoParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Parse parse(String raw) {
    if (!StringUtils.hasText(raw)) {
      return Parse.error("La IA devolvió una respuesta vacía.");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(GeminiJsonSanitizer.limpiar(raw));
    } catch (Exception e) {
      return Parse.error("Respuesta de la IA no es JSON válido.");
    }
    if (root == null || !root.isObject()) {
      return Parse.error("Respuesta de la IA no es un objeto JSON.");
    }
    List<Documento> documentos = documentos(root);
    int porcentaje = porcentaje(root, documentos);
    String etapaDetectada = texto(root, "etapa_procesal_detectada", "etapaProcesalDetectada", "etapa");
    String etapaNormalizada =
        CoactivaEtapa.fromTextoLibre(etapaDetectada).map(Enum::name).orElse(null);
    List<String> alertas = textos(root, "alertas_inconsistencias", "alertasInconsistencias", "alertas");
    Map<String, Object> datos = datos(root);
    String accion = texto(root, "siguiente_accion_sugerida", "siguienteAccionSugerida", "siguiente_accion");
    ObjectNode canon = objectMapper.createObjectNode();
    canon.put("porcentaje_completitud", porcentaje);
    canon.put("etapa_procesal_detectada", etapaNormalizada != null ? etapaNormalizada : etapaDetectada);
    canon.set("documentos_identificados", objectMapper.valueToTree(documentos));
    canon.set("alertas_inconsistencias", objectMapper.valueToTree(alertas));
    canon.set("datos_extraidos", objectMapper.valueToTree(datos));
    canon.put("siguiente_accion_sugerida", accion);
    String json;
    try {
      json = objectMapper.writeValueAsString(canon);
    } catch (Exception e) {
      return Parse.error("No se pudo serializar el diagnóstico.");
    }
    return Parse.ok(
        new CoactivaDiagnostico(
            porcentaje, etapaDetectada, etapaNormalizada, documentos, alertas, datos, accion, json));
  }

  public record Parse(CoactivaDiagnostico diagnostico, String error) {
    public static Parse ok(CoactivaDiagnostico diagnostico) {
      return new Parse(diagnostico, null);
    }

    public static Parse error(String error) {
      return new Parse(null, error);
    }

    public boolean ok() {
      return diagnostico != null;
    }
  }

  private static int porcentaje(JsonNode root, List<Documento> documentos) {
    JsonNode n = root.get("porcentaje_completitud");
    if (n == null) {
      n = root.get("porcentajeCompletitud");
    }
    if (n != null && !n.isNull()) {
      double v = n.isNumber() ? n.asDouble() : parseNumero(n.asText());
      if (!Double.isNaN(v)) {
        if (v > 0 && v <= 1) {
          v = v * 100;
        }
        return (int) Math.max(0, Math.min(100, Math.round(v)));
      }
    }
    if (documentos.isEmpty()) {
      return 0;
    }
    long presentes = documentos.stream().filter(Documento::presente).count();
    return (int) Math.round(100.0 * presentes / documentos.size());
  }

  private static double parseNumero(String raw) {
    if (!StringUtils.hasText(raw)) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(raw.replace("%", "").replace(',', '.').trim());
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private static List<Documento> documentos(JsonNode root) {
    JsonNode n = root.get("documentos_identificados");
    if (n == null) {
      n = root.get("documentosIdentificados");
    }
    List<Documento> items = new ArrayList<>();
    if (n == null || !n.isArray()) {
      return items;
    }
    for (JsonNode item : n) {
      if (!item.isObject()) {
        continue;
      }
      String tipo = texto(item, "tipo", "type");
      if (!StringUtils.hasText(tipo)) {
        continue;
      }
      items.add(new Documento(tipo.trim(), foja(item, "foja_inicio", "fojaInicio"), foja(item, "foja_fin", "fojaFin"), presente(item)));
    }
    return items;
  }

  private static boolean presente(JsonNode item) {
    JsonNode n = item.get("presente");
    if (n == null || n.isNull()) {
      return false;
    }
    if (n.isBoolean()) {
      return n.asBoolean();
    }
    String v = n.asText("").trim();
    return "true".equalsIgnoreCase(v) || "si".equalsIgnoreCase(v) || "sí".equalsIgnoreCase(v) || "1".equals(v);
  }

  private static Integer foja(JsonNode item, String... keys) {
    for (String key : keys) {
      JsonNode n = item.get(key);
      if (n == null || n.isNull()) {
        continue;
      }
      int v = n.isNumber() ? n.asInt() : (int) parseNumero(n.asText());
      if (v > 0) {
        return v;
      }
    }
    return null;
  }

  private static List<String> textos(JsonNode root, String... keys) {
    JsonNode n = null;
    for (String key : keys) {
      if (root.has(key)) {
        n = root.get(key);
        break;
      }
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

  private Map<String, Object> datos(JsonNode root) {
    JsonNode n = root.get("datos_extraidos");
    if (n == null) {
      n = root.get("datosExtraidos");
    }
    if (n == null || !n.isObject()) {
      return Map.of();
    }
    Map<String, Object> datos = new LinkedHashMap<>();
    n.fields()
        .forEachRemaining(
            e -> {
              JsonNode v = e.getValue();
              if (v == null || v.isNull()) {
                datos.put(e.getKey(), null);
              } else if (v.isNumber()) {
                datos.put(e.getKey(), v.numberValue());
              } else if (v.isBoolean()) {
                datos.put(e.getKey(), v.asBoolean());
              } else if (v.isContainerNode()) {
                datos.put(e.getKey(), objectMapper.convertValue(v, Object.class));
              } else {
                datos.put(e.getKey(), v.asText());
              }
            });
    return datos;
  }

  private static String texto(JsonNode root, String... keys) {
    for (String key : keys) {
      JsonNode n = root.get(key);
      if (n != null && !n.isNull() && !n.isContainerNode() && StringUtils.hasText(n.asText())) {
        return n.asText().trim();
      }
    }
    return null;
  }
}
