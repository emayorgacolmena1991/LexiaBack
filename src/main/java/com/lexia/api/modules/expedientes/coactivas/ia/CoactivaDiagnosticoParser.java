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

  /** Reglas de etapa del flujo coactivo. Van con el OCR consolidado, después del prompt de catálogo. */
  public static final String REGLAS_ETAPA =
      """

      TENTATIVA DE ETAPA. El mensaje de usuario es el OCR consolidado del PDF único (marcas --- FOJA n ---).
      La etapa del contexto es la reportada por el banco. etapa_sugerida se decide solo con el OCR y estas reglas.
      Gana el hito más avanzado que tenga extracto literal y foja. Sin esa evidencia, no avances.
      Citación válida: 1 notificación personal, o 2 boletas por persona, o correo fijado.
      Liquidación usada en providencia: alerta si pasaron más de 3 días desde su emisión.
      - PREVIA: pagaré a la orden, aviso de vencimiento, liquidación previa u orden de cobro con número de operación.
      - RPV: requerimiento de pago voluntario (10 días) con citación válida. Si está notificado y venció el término, puede seguir OPI.
      - OPI_EMITIDA: hay orden de pago inmediato (3 días) y no hay razón de notificación válida. Alerta crítica. No pasa a medidas.
      - NOTIFICACION_COA: la OPI consta notificada, con fecha en la razón. Si vencieron los 3 días, el siguiente hito posible es medidas, no antes.
      - MEDIDAS_CAUTELARES: providencia de ratificación o imposición, u oficio/matriz a Superintendencia de Bancos, SEPS, Ministerio del Trabajo, registrador o tránsito (ATM, Portovial, ANT). No inventes números de oficio.
      - ESCRITO: escrito del coactivado. Levantamiento solo por sueldo, pensión jubilar, alimentos o bonos del Estado.
      - EMBARGO: el banco informa valor retenido, o hay oficio de embargo y transferencia. La providencia de honorarios (10% + IVA) es previa a la transferencia.
      - HONORARIOS: respuesta de transferencia, matriz DataDoc, print COVIS de aplicación al crédito, o providencia/factura del SAE por el 10% recuperado + IVA.
      - AVALUO: informe con bien, perito, valor y fecha.
      - REMATE: convocatoria, postura, acta de remate o adjudicación.
      - CONVENIO: facilidad de pago a 36, 48 o 60 meses. Un solo convenio por proceso. 3 letras en mora inhabilitan.
      - ARCHIVADO: pago total, cancelación o archivo. No reabras etapas salvo deuda vigente explícita.
      """;

  public static final String FORMATO_JSON =
      """

      Responde ÚNICAMENTE con un objeto JSON válido, sin markdown ni texto adicional.
      etapa_sugerida es la tentativa. etapa_procesal_detectada debe ser igual a etapa_sugerida.codigo.
      Códigos: PREVIA|RPV|OPI_EMITIDA|NOTIFICACION_COA|MEDIDAS_CAUTELARES|ESCRITO|EMBARGO|HONORARIOS|AVALUO|REMATE|CONVENIO|ARCHIVADO.
      evidencia.extracto_texto es cita literal del OCR. Si no hay foja o fecha, usa null.
      {"porcentaje_completitud": 0,
       "etapa_procesal_detectada": "MEDIDAS_CAUTELARES",
       "etapa_sugerida": {
         "codigo": "MEDIDAS_CAUTELARES",
         "nombre": "Medidas cautelares",
         "justificacion_juridica": "por qué esta etapa y no la anterior",
         "evidencia": {
           "documento_origen": "nombre del PDF",
           "foja_pagina": 3,
           "fecha_documento": "2026-09-28",
           "extracto_texto": "cita literal corta"
         }
       },
       "suficiencia_documental": {
         "porcentaje_completitud": 0,
         "estado_diagnostico": "VERDE|AMARILLO|ROJO",
         "requisitos_faltantes": ["documento que el OCR no contiene"]
       },
       "acciones_sugeridas": [
         {"codigo_accion": "REQUERIR_LIQUIDACION", "titulo": "acción concreta", "descripcion": "por qué", "prioridad": "ALTA|MEDIA|BAJA"}
       ],
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
    JsonNode sugeridaIn = objeto(root, "etapa_sugerida");
    String etapaDetectada = sugeridaIn == null ? null : texto(sugeridaIn, "codigo", "code");
    if (!StringUtils.hasText(etapaDetectada)) {
      etapaDetectada = texto(root, "etapa_procesal_detectada", "etapaProcesalDetectada", "etapa");
    }
    String etapaNormalizada =
        CoactivaEtapa.fromTextoLibre(etapaDetectada).map(Enum::name).orElse(null);
    List<String> alertas = new ArrayList<>(textos(root, "alertas_inconsistencias", "alertasInconsistencias", "alertas"));
    JsonNode sufIn = objeto(root, "suficiencia_documental");
    if (sufIn != null) {
      for (String req : textos(sufIn, "requisitos_faltantes", "requisitosFaltantes")) {
        if (!alertas.contains(req)) {
          alertas.add(req);
        }
      }
    }
    Map<String, Object> datos = datos(root);
    String accion = texto(root, "siguiente_accion_sugerida", "siguienteAccionSugerida", "siguiente_accion");
    if (!StringUtils.hasText(accion)) {
      accion = primeraAccion(root);
    }
    ObjectNode canon = objectMapper.createObjectNode();
    canon.put("porcentaje_completitud", porcentaje);
    canon.put("etapa_procesal_detectada", etapaNormalizada != null ? etapaNormalizada : etapaDetectada);
    canon.set("etapa_sugerida", etapaSugerida(sugeridaIn, etapaNormalizada));
    canon.set("suficiencia_documental", suficiencia(sufIn, porcentaje));
    canon.set("acciones_sugeridas", acciones(root));
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

  private ObjectNode etapaSugerida(JsonNode in, String etapaNormalizada) {
    ObjectNode node = in != null && in.isObject() ? (ObjectNode) in.deepCopy() : objectMapper.createObjectNode();
    if (etapaNormalizada != null) {
      node.put("codigo", etapaNormalizada);
      if (!StringUtils.hasText(texto(node, "nombre"))) {
        node.put("nombre", CoactivaEtapa.parse(etapaNormalizada).map(CoactivaEtapa::label).orElse(etapaNormalizada));
      }
    }
    return node;
  }

  private ObjectNode suficiencia(JsonNode in, int porcentaje) {
    ObjectNode node = in != null && in.isObject() ? (ObjectNode) in.deepCopy() : objectMapper.createObjectNode();
    node.put("porcentaje_completitud", porcentaje);
    node.put("estado_diagnostico", porcentaje > 90 ? "VERDE" : porcentaje >= 70 ? "AMARILLO" : "ROJO");
    if (!node.has("requisitos_faltantes") || !node.get("requisitos_faltantes").isArray()) {
      node.putArray("requisitos_faltantes");
    }
    return node;
  }

  private JsonNode acciones(JsonNode root) {
    JsonNode n = root.get("acciones_sugeridas");
    if (n == null) {
      n = root.get("accionesSugeridas");
    }
    return n != null && n.isArray() ? n.deepCopy() : objectMapper.createArrayNode();
  }

  private static String primeraAccion(JsonNode root) {
    JsonNode n = root.get("acciones_sugeridas");
    if (n == null) {
      n = root.get("accionesSugeridas");
    }
    if (n == null || !n.isArray() || n.isEmpty()) {
      return null;
    }
    String titulo = texto(n.get(0), "titulo");
    return StringUtils.hasText(titulo) ? titulo : texto(n.get(0), "descripcion");
  }

  private static JsonNode objeto(JsonNode root, String key) {
    JsonNode n = root.get(key);
    return n != null && n.isObject() ? n : null;
  }

  private static int porcentaje(JsonNode root, List<Documento> documentos) {
    JsonNode n = root.get("porcentaje_completitud");
    if (n == null || n.isNull()) {
      n = root.get("porcentajeCompletitud");
    }
    if (n == null || n.isNull()) {
      JsonNode suf = objeto(root, "suficiencia_documental");
      if (suf != null) {
        n = suf.get("porcentaje_completitud");
        if (n == null || n.isNull()) {
          n = suf.get("porcentajeCompletitud");
        }
      }
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
