package com.lexia.api.modules.ia.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCapturaBiess;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionMinutaVivienda;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Análisis de expediente con Claude vía API Messages: una sola llamada por expediente con tool use
 * forzado (extracción por documento + consolidado + dictamen).
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "claude")
public class ClaudeAnalysisService implements AnalisisDocumentoService {

  private static final Logger LOG = LoggerFactory.getLogger(ClaudeAnalysisService.class);

  private static final String API_URL = "https://api.anthropic.com/v1/messages";
  private static final String API_VERSION = "2023-06-01";
  private static final String EXPEDIENTE_TOOL_NAME = ProcesarExpedienteCompletoPayload.TOOL_NAME;
  private static final String MINUTA_VIVIENDA_TOOL_NAME = "registrar_datos_minuta_vivienda";
  private static final String CAPTURA_BIESS_TOOL_NAME = "registrar_datos_captura_biess";
  private static final int MAX_INTENTOS_CAPTURA_BIESS = 2;
  private static final int MAX_CHARS_CAPTURA = 20_000;
  private static final int MAX_TOKENS = 4096;
  private static final int MAX_TOKENS_EXPEDIENTE = 8192;
  private static final int MAX_CHARS_OCR = 120_000;
  private static final int MAX_INTENTOS_POR_MODELO = 4;
  private static final long BACKOFF_BASE_MS = 1500L;

  private static final String EXPEDIENTE_SYSTEM_PROMPT_FALLBACK =
      """
      Eres un sistema experto en validación y cotejo notarial.
      Recibirás el texto OCR del expediente; cada archivo en su propio <documento id> dentro de
      <expediente_ocr>.

      TAREAS EN ESTA ÚNICA LLAMADA:
      1. EXTRAER datosClave de cada documento (para el cotejo entre documentos).
      2. CONSOLIDAR comprador, vendedor e inmueble.
      3. DICTAMINAR: compara Cédula vs Papeleta y Avalúo vs Historia de Dominio; reporta
         discrepancias, vigencias vencidas y gravámenes.

      Responde exclusivamente invocando la herramienta %s.
      """
          .formatted(EXPEDIENTE_TOOL_NAME);

  private final String apiKey;
  private final List<String> modelos;
  private final ObjectMapper objectMapper;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public ClaudeAnalysisService(
      @Value("${anthropic.api.key:}") String apiKey,
      @Value("${anthropic.models:claude-sonnet-5,claude-haiku-4-5-20251001}") String modelos,
      ObjectMapper objectMapper) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.modelos =
        Arrays.stream(modelos.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean isConfigured() {
    return StringUtils.hasText(apiKey);
  }

  @Override
  public ExtraccionExpedienteCompleto procesarExpedienteCompleto(
      String ocrMarcado, String systemPrompt) {
    String texto = ocrMarcado == null ? "" : ocrMarcado;
    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionExpedienteCompleto.error("Sin texto OCR para procesar el expediente.");
    }
    if (!isConfigured()) {
      return ExtraccionExpedienteCompleto.error("ANTHROPIC_API_KEY no configurada.");
    }
    String prompt =
        (StringUtils.hasText(systemPrompt) ? systemPrompt : EXPEDIENTE_SYSTEM_PROMPT_FALLBACK)
            + ProcesarExpedienteCompletoPayload.REGLAS_SALIDA;

    String ultimoDiagnostico = "sin respuesta";
    for (String modelo : modelos) {
      for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
        try {
          LOG.info(
              "Claude expediente completo: modelo={} intento={}/{}",
              modelo,
              intento,
              MAX_INTENTOS_POR_MODELO);
          HttpResponse<String> res = enviar(construirBodyExpedienteCompleto(modelo, prompt, texto));
          int status = res.statusCode();
          if (status == 200) {
            JsonNode root = objectMapper.readTree(res.body());
            if ("max_tokens".equals(root.path("stop_reason").asText())) {
              return ExtraccionExpedienteCompleto.error(
                  "Respuesta truncada (max_tokens). Reducir el expediente.");
            }
            ProcesarExpedienteCompletoPayload payload =
                leerToolUse(root, ProcesarExpedienteCompletoPayload.class);
            if (payload != null && payload.datosExtraidos() != null && payload.dictamen() != null) {
              LOG.info(
                  "Claude expediente completo OK modelo={} documentos={}",
                  modelo,
                  payload.documentosExtraidos().size());
              return ExtraccionExpedienteCompleto.ok(payload);
            }
            ultimoDiagnostico = "modelo=" + modelo + " sin tool_use " + EXPEDIENTE_TOOL_NAME;
            LOG.warn(ultimoDiagnostico);
            dormir(BACKOFF_BASE_MS * intento);
            continue;
          }
          ultimoDiagnostico =
              "modelo=" + modelo + " HTTP " + status + ": " + truncate(res.body(), 180);
          LOG.warn("Fallo Claude expediente completo {}", ultimoDiagnostico);
          if (status == 404) {
            break;
          }
          if (status == 429 || status == 529 || status >= 500) {
            dormir(esperaReintento(res, intento));
            continue;
          }
          return ExtraccionExpedienteCompleto.error(ultimoDiagnostico);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return ExtraccionExpedienteCompleto.error("Procesamiento interrumpido.");
        } catch (Exception e) {
          String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          ultimoDiagnostico = "modelo=" + modelo + " " + truncate(msg, 180);
          LOG.warn("Fallo Claude expediente completo: {}", ultimoDiagnostico);
          dormir(BACKOFF_BASE_MS * intento);
        }
      }
    }
    return ExtraccionExpedienteCompleto.error(
        "No se pudo procesar el expediente con Claude. Último: " + ultimoDiagnostico);
  }

  @Override
  public ExtraccionMinutaVivienda extraerMinutaVivienda(String ocrConsolidado) {
    String texto = ocrConsolidado == null ? "" : ocrConsolidado;
    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionMinutaVivienda.error("Sin texto OCR consolidado para minuta.");
    }
    if (!isConfigured()) {
      return ExtraccionMinutaVivienda.error("ANTHROPIC_API_KEY no configurada.");
    }

    String ultimoDiagnostico = "sin respuesta";
    for (String modelo : modelos) {
      for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
        try {
          LOG.info(
              "Claude minuta vivienda: modelo={} intento={}/{}",
              modelo,
              intento,
              MAX_INTENTOS_POR_MODELO);
          HttpResponse<String> res = enviar(construirBodyMinutaVivienda(modelo, texto));
          int status = res.statusCode();
          if (status == 200) {
            JsonNode root = objectMapper.readTree(res.body());
            if ("max_tokens".equals(root.path("stop_reason").asText())) {
              return ExtraccionMinutaVivienda.error(
                  "Respuesta truncada (max_tokens) al extraer minuta.");
            }
            MinutaViviendaData data = leerToolUse(root, MinutaViviendaData.class);
            if (data != null) {
              return ExtraccionMinutaVivienda.ok(data);
            }
            ultimoDiagnostico = "modelo=" + modelo + " sin tool_use minuta válido";
          } else if (status == 429 || status >= 500) {
            ultimoDiagnostico = "modelo=" + modelo + " HTTP " + status;
            dormir(esperaReintento(res, intento));
          } else {
            ultimoDiagnostico =
                "modelo=" + modelo + " HTTP " + status + " " + truncate(res.body(), 180);
            return ExtraccionMinutaVivienda.error(ultimoDiagnostico);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return ExtraccionMinutaVivienda.error("Extracción de minuta interrumpida.");
        } catch (Exception e) {
          String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          ultimoDiagnostico = "modelo=" + modelo + " " + truncate(msg, 180);
          LOG.warn("Fallo Claude minuta: {}", ultimoDiagnostico);
          dormir(BACKOFF_BASE_MS * intento);
        }
      }
    }
    return ExtraccionMinutaVivienda.error(
        "No se pudo extraer datos de minuta. Último: " + ultimoDiagnostico);
  }

  @Override
  public ExtraccionCapturaBiess extraerCapturaBiess(String textoCaptura) {
    String texto = textoCaptura == null ? "" : textoCaptura;
    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionCapturaBiess.error("La captura BIESS no contiene texto legible.");
    }
    if (!isConfigured()) {
      return ExtraccionCapturaBiess.error("ANTHROPIC_API_KEY no configurada.");
    }
    String ultimoDiagnostico = "sin respuesta";
    for (String modelo : modelos) {
      for (int intento = 1; intento <= MAX_INTENTOS_CAPTURA_BIESS; intento++) {
        try {
          HttpResponse<String> res = enviar(construirBodyCapturaBiess(modelo, texto));
          int status = res.statusCode();
          if (status == 200) {
            DatosBiessMinuta data =
                leerToolUse(objectMapper.readTree(res.body()), DatosBiessMinuta.class);
            if (data != null) {
              return ExtraccionCapturaBiess.ok(data);
            }
            ultimoDiagnostico = "modelo=" + modelo + " sin tool_use captura BIESS";
          } else if (status == 429 || status >= 500) {
            ultimoDiagnostico = "modelo=" + modelo + " HTTP " + status;
            dormir(esperaReintento(res, intento));
          } else {
            return ExtraccionCapturaBiess.error(
                "modelo=" + modelo + " HTTP " + status + " " + truncate(res.body(), 180));
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return ExtraccionCapturaBiess.error("Extracción de captura BIESS interrumpida.");
        } catch (Exception e) {
          String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          ultimoDiagnostico = "modelo=" + modelo + " " + truncate(msg, 180);
          LOG.warn("Fallo Claude captura BIESS: {}", ultimoDiagnostico);
          dormir(BACKOFF_BASE_MS * intento);
        }
      }
    }
    return ExtraccionCapturaBiess.error(
        "No se pudo leer la captura BIESS. Último: " + ultimoDiagnostico);
  }

  private String construirBodyCapturaBiess(String modelo, String texto) throws IOException {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (String field : new String[] {"monto", "tasa", "plazo", "apoderado"}) {
      props
          .putObject(field)
          .put("type", "string")
          .put("description", "Valor de " + field + " tal como aparece; cadena vacía si no está.");
      required.add(field);
    }

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", 512);
    body.put("system", CAPTURA_BIESS_PROMPT);
    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", CAPTURA_BIESS_TOOL_NAME);
    tool.put("description", "Registra monto, tasa, plazo y apoderado de la captura BIESS.");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", CAPTURA_BIESS_TOOL_NAME);
    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        "<captura_biess_ocr>\n" + truncate(texto, MAX_CHARS_CAPTURA) + "\n</captura_biess_ocr>");
    return objectMapper.writeValueAsString(body);
  }

  private String construirBodyMinutaVivienda(String modelo, String texto) throws IOException {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    String[] fields = {
      "nombre_conyuge_1",
      "cedula_conyuge_1",
      "nombre_conyuge_2",
      "cedula_conyuge_2",
      "profesion_conyuge_1",
      "profesion_conyuge_2",
      "canton_domicilio",
      "nombre_afiliado",
      "descripcion_inmuebles_antecedentes",
      "descripcion_inmueble_hipoteca",
      "parroquia_inmueble",
      "canton_inmueble",
      "provincia_inmueble",
      "lindero_norte",
      "lindero_sur",
      "lindero_este",
      "lindero_oeste",
      "superficie_m2",
      "estado_civil",
      "monto_prestamo",
      "monto_prestamo_letras",
      "plazo_credito",
      "tasa_interes_inicial",
      "institucion_financiera_original",
      "direccion_deudor",
      "telefono_deudor",
      "correo_deudor",
      "ciudad_firma",
      "fecha_firma"
    };
    ArrayNode required = schema.putArray("required");
    for (String field : fields) {
      ObjectNode prop = props.putObject(field);
      prop.put("type", "string");
      prop.put(
          "description",
          "Valor del campo "
              + field
              + ". Si no aparece en el OCR, usa exactamente nodata.");
      required.add(field);
    }

    String system =
        """
        Eres un asistente legal experto en minutas y contratos de mutuo hipotecario BIESS (Ecuador).
        Analiza el texto OCR del expediente y extrae exactamente los campos de la herramienta.
        Campos de identidad/inmueble (minuta) y de crédito/contacto (contrato de mutuo).
        REGLAS:
        1. Si un dato no está presente o es ilegible, usa exactamente la cadena nodata.
        2. No inventes montos, tasas, plazos, cédulas ni nombres.
        3. monto_prestamo: cifra en números (ej. 45000.00). monto_prestamo_letras: en palabras.
        4. institucion_financiera_original: banco acreedor anterior a cancelar (sustitución).
        5. No agregues texto fuera de la herramienta.
        """;

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", MAX_TOKENS);
    body.put("system", system);

    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", MINUTA_VIVIENDA_TOOL_NAME);
    tool.put(
        "description",
        "Registra datos para minuta de hipoteca y contrato de mutuo (vivienda hipotecada BIESS).");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", MINUTA_VIVIENDA_TOOL_NAME);

    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        "Extrae los datos de minuta/contrato de mutuo del siguiente expediente OCR.\n\n<expediente_ocr>\n"
            + truncate(texto, MAX_CHARS_OCR)
            + "\n</expediente_ocr>");

    return objectMapper.writeValueAsString(body);
  }

  private HttpResponse<String> enviar(String body) throws IOException, InterruptedException {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(API_URL))
            .timeout(Duration.ofSeconds(180))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private String construirBodyExpedienteCompleto(String modelo, String systemPrompt, String texto)
      throws IOException {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.set("documentosExtraidos", schemaDocumentosExtraidos());
    props.set("datosConsolidados", schemaDatosConsolidados());
    props.set("dictamen", schemaDictamen());
    schema.putArray("required").add("documentosExtraidos").add("datosConsolidados").add("dictamen");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", MAX_TOKENS_EXPEDIENTE);
    body.put("system", systemPrompt);

    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", EXPEDIENTE_TOOL_NAME);
    tool.put(
        "description",
        "Ejecuta extracción en lote por documento y dictamen de titulación global.");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", EXPEDIENTE_TOOL_NAME);

    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    String marcado =
        texto.contains("<expediente_ocr>")
            ? texto
            : "<expediente_ocr>\n" + texto + "\n</expediente_ocr>";
    msg.put(
        "content",
        "Procesa el expediente. Cada archivo va en su propio <documento id>. No inventes datos.\n\n"
            + truncate(marcado, MAX_CHARS_OCR));
    return objectMapper.writeValueAsString(body);
  }

  private ObjectNode schemaDocumentosExtraidos() {
    ObjectNode item = objectMapper.createObjectNode();
    item.put("type", "object");
    ObjectNode props = item.putObject("properties");
    props
        .putObject("documentoId")
        .put("type", "string")
        .put("description", "Mismo id del <documento> de entrada.");
    props
        .putObject("tipoDocumento")
        .put("type", "string")
        .put("description", "Nombre exacto o identificado del documento.");
    props
        .putObject("resumen")
        .put("type", "string")
        .put("description", "Descripción breve (1 a 2 oraciones) del contenido.");
    props
        .putObject("datosClave")
        .put("type", "object")
        .put(
            "description",
            "Pares clave-valor del documento: nombres, identificaciones, fechas, montos, linderos,"
                + " números de registro, etc.")
        .put("additionalProperties", true);
    item.putArray("required").add("documentoId").add("tipoDocumento").add("resumen").add("datosClave");

    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "array");
    node.set("items", item);
    return node;
  }

  private ObjectNode schemaDatosConsolidados() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "object");
    ObjectNode props = node.putObject("properties");
    props.set("comprador", schemaPersona());
    props.set("vendedor", schemaPersona());
    props.set("inmueble", schemaInmueble());
    node.putArray("required").add("comprador").add("vendedor").add("inmueble");
    return node;
  }

  private ObjectNode schemaPersona() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "object");
    ObjectNode props = node.putObject("properties");
    props.putObject("nombres").put("type", "string");
    props.putObject("cedula").put("type", "string");
    props.putObject("estadoCivil").put("type", "string");
    node.putArray("required").add("nombres").add("cedula").add("estadoCivil");
    return node;
  }

  private ObjectNode schemaInmueble() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "object");
    ObjectNode props = node.putObject("properties");
    props.putObject("claveCatastral").put("type", "string");
    props.putObject("linderos").put("type", "string");
    props.putObject("avaluo").put("type", "number");
    node.putArray("required").add("claveCatastral").add("linderos").add("avaluo");
    return node;
  }

  private ObjectNode schemaDictamen() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", "object");
    ObjectNode props = node.putObject("properties");
    ObjectNode estado = props.putObject("estado");
    estado.put("type", "string");
    estado.putArray("enum").add("APPROVED").add("WITH_OBSERVATIONS").add("REJECTED");
    props.putObject("resumen").put("type", "string");
    ObjectNode obs = props.putObject("observaciones");
    obs.put("type", "array");
    ObjectNode item = obs.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");
    itemProps.putObject("codigo").put("type", "string");
    ObjectNode sev = itemProps.putObject("severidad");
    sev.put("type", "string");
    sev.putArray("enum").add("HIGH").add("MEDIUM").add("LOW");
    itemProps.putObject("mensaje").put("type", "string");
    item.putArray("required").add("codigo").add("severidad").add("mensaje");
    node.putArray("required").add("estado").add("resumen").add("observaciones");
    return node;
  }

  private <T> T leerToolUse(JsonNode root, Class<T> type) throws IOException {
    for (JsonNode block : root.path("content")) {
      if ("tool_use".equals(block.path("type").asText())) {
        JsonNode input = block.path("input");
        if (input.isObject()) {
          return objectMapper.treeToValue(input, type);
        }
      }
    }
    return null;
  }

  private static long esperaReintento(HttpResponse<String> res, int intento) {
    long ms = Math.min(12_000L, BACKOFF_BASE_MS * intento * 2);
    try {
      long retryAfterSeg = res.headers().firstValueAsLong("retry-after").orElse(0L);
      if (retryAfterSeg > 0) {
        ms = Math.min(30_000L, retryAfterSeg * 1000L);
      }
    } catch (NumberFormatException ignored) {
      // header raro → se queda el backoff propio
    }
    return ms;
  }

  private static void dormir(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max);
  }
}
