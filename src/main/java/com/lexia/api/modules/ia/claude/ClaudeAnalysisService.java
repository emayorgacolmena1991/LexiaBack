package com.lexia.api.modules.ia.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaVariableBinder;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.LlmTimeoutException;
import com.lexia.api.modules.ia.llm.IaConsumoLog;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionCapturaBiess;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionMinutaVivienda;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
  private static final String VALIDACION_TOOL_NAME = "validar_documento_coactivo";
  private static final String DIAGNOSTICO_TOOL_NAME = "diagnosticar_expediente_coactivo";
  private static final int MAX_CHARS_VALIDACION = 80_000;
  private static final int MAX_TOKENS_VALIDACION = 1024;
  private static final int MAX_CHARS_DIAGNOSTICO = 120_000;
  /** Tope de la primera llamada. El fallo de expedientes grandes es este límite, no el timeout. */
  private static final int MAX_TOKENS_DIAGNOSTICO = 8192;
  /** Un solo reintento, con esquema reducido, si la primera respuesta llega con stop_reason max_tokens. */
  private static final int MAX_TOKENS_DIAGNOSTICO_BREVE = 1024;
  private static final String ERROR_DIAGNOSTICO_TRUNCADO =
      "Respuesta truncada (max_tokens) al diagnosticar.";
  /** Supera el candado de app.jobs: el latido del worker lo renueva cada 30 s mientras espera. */
  private static final Duration TIMEOUT_DIAGNOSTICO = Duration.ofSeconds(180);
  /** Tope de escrituración y del resto de llamadas Claude. */
  private static final Duration TIMEOUT_DEFAULT = Duration.ofSeconds(120);
  private static final int MAX_INTENTOS_CAPTURA_BIESS = 2;
  private static final int MAX_CHARS_CAPTURA = 20_000;
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
  private final ActoVariableCatalog actos;
  private final IaConsumoLog consumo;
  private final ThreadLocal<List<String>> modelosForzados = new ThreadLocal<>();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public ClaudeAnalysisService(
      @Value("${anthropic.api.key:}") String apiKey,
      @Value("${anthropic.models:claude-sonnet-5,claude-haiku-4-5-20251001}") String modelos,
      ObjectMapper objectMapper,
      ActoVariableCatalog actos,
      IaConsumoLog consumo) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.modelos =
        Arrays.stream(modelos.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    this.objectMapper = objectMapper;
    this.actos = actos;
    this.consumo = consumo;
  }

  @Override
  public boolean isConfigured() {
    return StringUtils.hasText(apiKey);
  }

  /** Cotejo estricto: solo Haiku o Flash, sin caer al modelo pesado. */
  @Override
  public ExtraccionExpedienteCompleto procesarCotejo(String ocrMarcado, String systemPrompt) {
    List<String> livianos = solo(true);
    if (livianos.isEmpty()) {
      return ExtraccionExpedienteCompleto.error("No hay modelo liviano (Haiku o Flash) configurado.");
    }
    modelosForzados.set(livianos);
    try {
      return procesarExpedienteCompleto(ocrMarcado, systemPrompt);
    } finally {
      modelosForzados.remove();
    }
  }

  /** Dictamen: solo el modelo pesado, cuando el cotejo dejó observaciones altas o medias. */
  @Override
  public ExtraccionExpedienteCompleto procesarDictamen(String ocrMarcado, String systemPrompt) {
    List<String> pesados = solo(false);
    if (pesados.isEmpty()) {
      return ExtraccionExpedienteCompleto.error("No hay modelo pesado configurado.");
    }
    modelosForzados.set(pesados);
    try {
      return procesarExpedienteCompleto(ocrMarcado, systemPrompt);
    } finally {
      modelosForzados.remove();
    }
  }

  private List<String> solo(boolean liviano) {
    List<String> elegidos = new ArrayList<>();
    for (String modelo : modelos) {
      boolean esLiviano = esLiviano(modelo);
      if (liviano == esLiviano) {
        elegidos.add(modelo);
      }
    }
    return elegidos;
  }

  private static boolean esLiviano(String modelo) {
    String nombre = modelo.toLowerCase(Locale.ROOT);
    return nombre.contains("haiku") || nombre.contains("flash");
  }

  /** Cotejo con respaldo: Haiku o Flash primero. La minuta sigue el orden configurado. */
  private List<String> modelosExtraccion() {
    List<String> livianos = new ArrayList<>();
    List<String> pesados = new ArrayList<>();
    for (String modelo : modelos) {
      String nombre = modelo.toLowerCase(Locale.ROOT);
      if (nombre.contains("haiku") || nombre.contains("flash")) {
        livianos.add(modelo);
      } else {
        pesados.add(modelo);
      }
    }
    livianos.addAll(pesados);
    return livianos;
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
    List<String> orden = modelosForzados.get() == null ? modelosExtraccion() : modelosForzados.get();
    for (String modelo : orden) {
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
        } catch (LlmTimeoutException timeout) {
          throw timeout;
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
  public ExtraccionMinutaVivienda extraerMinutaVivienda(
      String ocrConsolidado, String productCode, String templateKind) {
    String texto = ocrConsolidado == null ? "" : ocrConsolidado;
    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionMinutaVivienda.error("Sin texto OCR consolidado para minuta.");
    }
    if (!isConfigured()) {
      return ExtraccionMinutaVivienda.error("ANTHROPIC_API_KEY no configurada.");
    }

    String ultimoDiagnostico = "sin respuesta";
    List<String> orden = solo(true);
    if (orden.isEmpty()) {
      return ExtraccionMinutaVivienda.error("No hay modelo Haiku configurado para la minuta.");
    }
    for (String modelo : orden) {
      for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
        try {
          LOG.info(
              "Claude minuta vivienda: modelo={} intento={}/{}",
              modelo,
              intento,
              MAX_INTENTOS_POR_MODELO);
          HttpResponse<String> res =
              enviar(construirBodyMinutaVivienda(modelo, texto, productCode, templateKind));
          int status = res.statusCode();
          if (status == 200) {
            JsonNode root = objectMapper.readTree(res.body());
            if ("max_tokens".equals(root.path("stop_reason").asText())) {
              return ExtraccionMinutaVivienda.error(
                  "Respuesta truncada (max_tokens) al extraer minuta.");
            }
            JsonNode input = leerToolInput(root);
            if (input != null) {
              return ExtraccionMinutaVivienda.ok(MinutaVariableBinder.bind(input, objectMapper));
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
        } catch (LlmTimeoutException timeout) {
          throw timeout;
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
  public ValidacionDocumentoJson validarDocumento(String systemPrompt, String textoDocumento) {
    String texto = textoDocumento == null ? "" : textoDocumento;
    if (!StringUtils.hasText(texto.trim())) {
      return ValidacionDocumentoJson.error("Sin texto OCR para validar el documento.");
    }
    if (!isConfigured()) {
      return ValidacionDocumentoJson.error("ANTHROPIC_API_KEY no configurada.");
    }
    String prompt =
        StringUtils.hasText(systemPrompt)
            ? systemPrompt
            : "Eres un auditor legal. Valida el documento invocando " + VALIDACION_TOOL_NAME + ".";
    String ultimoDiagnostico = "sin respuesta";
    for (String modelo : modelos) {
      for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
        try {
          HttpResponse<String> res = enviar(construirBodyValidacion(modelo, prompt, texto));
          int status = res.statusCode();
          if (status == 200) {
            JsonNode root = objectMapper.readTree(res.body());
            if ("max_tokens".equals(root.path("stop_reason").asText())) {
              return ValidacionDocumentoJson.error("Respuesta truncada (max_tokens) al validar.");
            }
            JsonNode input = leerToolInput(root);
            if (input != null && input.isObject()) {
              LOG.info("Claude validación documental OK modelo={}", modelo);
              return ValidacionDocumentoJson.ok(objectMapper.writeValueAsString(input));
            }
            ultimoDiagnostico = "modelo=" + modelo + " sin tool_use " + VALIDACION_TOOL_NAME;
          } else if (status == 429 || status == 529 || status >= 500) {
            ultimoDiagnostico = "modelo=" + modelo + " HTTP " + status;
            dormir(esperaReintento(res, intento));
            continue;
          } else {
            return ValidacionDocumentoJson.error(
                "modelo=" + modelo + " HTTP " + status + " " + truncate(res.body(), 180));
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return ValidacionDocumentoJson.error("Validación documental interrumpida.");
        } catch (LlmTimeoutException timeout) {
          throw timeout;
        } catch (Exception e) {
          String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          ultimoDiagnostico = "modelo=" + modelo + " " + truncate(msg, 180);
          LOG.warn("Fallo Claude validación documental: {}", ultimoDiagnostico);
          dormir(BACKOFF_BASE_MS * intento);
        }
      }
    }
    return ValidacionDocumentoJson.error(
        "No se pudo validar el documento con Claude. Último: " + ultimoDiagnostico);
  }

  @Override
  public ValidacionDocumentoJson diagnosticarCoactiva(String systemPrompt, String textoExpediente) {
    String texto = textoExpediente == null ? "" : textoExpediente;
    if (!StringUtils.hasText(texto.trim())) {
      return ValidacionDocumentoJson.error("Sin texto OCR para diagnosticar el expediente.");
    }
    if (!isConfigured()) {
      return ValidacionDocumentoJson.error("ANTHROPIC_API_KEY no configurada.");
    }
    String prompt =
        StringUtils.hasText(systemPrompt)
            ? systemPrompt
            : "Eres un auditor legal. Diagnostica el expediente invocando " + DIAGNOSTICO_TOOL_NAME + ".";
    String ultimoDiagnostico = "sin respuesta";
    for (String modelo : modelos) {
      IntentoDiagnostico intento = pedirDiagnostico(modelo, prompt, texto, false);
      if (intento.cortada()) {
        LOG.warn("Claude diagnóstico truncado modelo={}; un reintento con esquema breve", modelo);
        IntentoDiagnostico breve = pedirDiagnostico(modelo, prompt, texto, true);
        if (breve.json() != null) {
          LOG.info("Claude diagnóstico coactivo recuperado modelo={}", modelo);
          return ValidacionDocumentoJson.ok(breve.json());
        }
        return ValidacionDocumentoJson.error(
            breve.cortada() ? ERROR_DIAGNOSTICO_TRUNCADO : breve.error());
      }
      if (intento.json() != null) {
        LOG.info("Claude diagnóstico coactivo OK modelo={}", modelo);
        return ValidacionDocumentoJson.ok(intento.json());
      }
      if (intento.detener()) {
        return ValidacionDocumentoJson.error(intento.error());
      }
      ultimoDiagnostico = intento.error();
    }
    return ValidacionDocumentoJson.error(
        "No se pudo diagnosticar el expediente con Claude. Último: " + ultimoDiagnostico);
  }

  /**
   * Una pasada por modelo. {@code breve} no reintenta transporte: es el único rescate tras
   * {@code max_tokens} y no debe encadenarse.
   */
  private IntentoDiagnostico pedirDiagnostico(
      String modelo, String prompt, String texto, boolean breve) {
    int tope = breve ? 1 : MAX_INTENTOS_POR_MODELO;
    String ultimo = "sin respuesta";
    for (int intento = 1; intento <= tope; intento++) {
      try {
        HttpResponse<String> res =
            enviar(construirBodyDiagnostico(modelo, prompt, texto, breve), TIMEOUT_DIAGNOSTICO);
        int status = res.statusCode();
        if (status == 200) {
          JsonNode root = objectMapper.readTree(res.body());
          if ("max_tokens".equals(root.path("stop_reason").asText())) {
            return IntentoDiagnostico.porMaxTokens();
          }
          JsonNode input = leerToolInput(root);
          if (input != null && input.isObject()) {
            return IntentoDiagnostico.ok(objectMapper.writeValueAsString(input));
          }
          ultimo = "modelo=" + modelo + " sin tool_use " + DIAGNOSTICO_TOOL_NAME;
          if (!breve) {
            dormir(BACKOFF_BASE_MS * intento);
          }
          continue;
        }
        if (status == 429 || status == 529 || status >= 500) {
          ultimo = "modelo=" + modelo + " HTTP " + status;
          if (!breve) {
            dormir(esperaReintento(res, intento));
            continue;
          }
          return IntentoDiagnostico.agotado(ultimo);
        }
        return IntentoDiagnostico.fallo(
            "modelo=" + modelo + " HTTP " + status + " " + truncate(res.body(), 180));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return IntentoDiagnostico.fallo("Diagnóstico coactivo interrumpido.");
      } catch (LlmTimeoutException timeout) {
        throw timeout;
      } catch (Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        ultimo = "modelo=" + modelo + " " + truncate(msg, 180);
        LOG.warn("Fallo Claude diagnóstico coactivo: {}", ultimo);
        if (!breve) {
          dormir(BACKOFF_BASE_MS * intento);
        }
      }
    }
    return IntentoDiagnostico.agotado(ultimo);
  }

  /** JSON de tool use, o error. {@code cortada} no es un diagnóstico: el input puede venir a medias. */
  private record IntentoDiagnostico(String json, String error, boolean cortada, boolean detener) {
    static IntentoDiagnostico ok(String json) {
      return new IntentoDiagnostico(json, null, false, false);
    }

    static IntentoDiagnostico porMaxTokens() {
      return new IntentoDiagnostico(null, ERROR_DIAGNOSTICO_TRUNCADO, true, false);
    }

    static IntentoDiagnostico fallo(String error) {
      return new IntentoDiagnostico(null, error, false, true);
    }

    static IntentoDiagnostico agotado(String error) {
      return new IntentoDiagnostico(null, error, false, false);
    }
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
            LOG.info("Claude captura BIESS modelo={} respuesta cruda: {}", modelo, res.body());
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
        } catch (LlmTimeoutException timeout) {
          throw timeout;
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

  private String construirBodyValidacion(String modelo, String systemPrompt, String texto)
      throws IOException {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ObjectNode estado = props.putObject("estado");
    estado.put("type", "string");
    estado.putArray("enum").add("APROBADO").add("RECHAZADO");
    props
        .putObject("confianza")
        .put("type", "integer")
        .put("description", "Confianza 0 a 100.");
    props
        .putObject("razon_rechazo")
        .put("type", "string")
        .put("description", "Motivo concreto si RECHAZADO; cadena vacía si APROBADO.");
    ObjectNode checklist = props.putObject("checklist_cumplido");
    checklist.put("type", "array");
    checklist.putObject("items").put("type", "string");
    schema.putArray("required").add("estado").add("confianza").add("razon_rechazo").add("checklist_cumplido");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", MAX_TOKENS_VALIDACION);
    body.put("system", systemPrompt);
    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", VALIDACION_TOOL_NAME);
    tool.put("description", "Registra el dictamen de validación documental en JSON estricto.");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", VALIDACION_TOOL_NAME);
    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        "Valida este documento. No inventes datos.\n\n<documento_ocr>\n"
            + truncate(texto, MAX_CHARS_VALIDACION)
            + "\n</documento_ocr>");
    return objectMapper.writeValueAsString(body);
  }

  private String construirBodyDiagnostico(
      String modelo, String systemPrompt, String texto, boolean breve) throws IOException {
    ObjectNode schema = breve ? schemaDiagnosticoBreve() : schemaDiagnostico();
    String system =
        breve
            ? systemPrompt
                + "\n\nPRIORIDAD: la respuesta anterior se truncó. Ignora catálogos y listas."
                + " Devuelve solo etapa_sugerida (codigo, justificacion_juridica de máximo dos frases,"
                + " y una evidencia). Sin cita literal: codigo INDETERMINADA y evidencia null."
                + " No inventes extractos."
            : systemPrompt;
    String instruccion =
        breve
            ? "Una sola etapa, justificación de máximo dos frases y una cita literal de hasta 180 caracteres,"
                + " con documento, fecha y foja solo si constan. No inventes evidencia.\n\n<expediente_ocr>\n"
            : "Diagnostica una sola etapa, la más probable. Justificación de máximo dos frases."
                + " Una cita literal de hasta 180 caracteres, con documento, fecha y foja solo si constan."
                + " No inventes evidencia, pero SÍ devuelve null explícito en claves sin dato. No omitas claves.\n\n<expediente_ocr>\n";
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", breve ? MAX_TOKENS_DIAGNOSTICO_BREVE : MAX_TOKENS_DIAGNOSTICO);
    body.put("system", system);
    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", DIAGNOSTICO_TOOL_NAME);
    tool.put(
        "description",
        breve
            ? "Registra solo la etapa más probable y una evidencia corta."
            : "Registra el diagnóstico corto del expediente coactivo.");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", DIAGNOSTICO_TOOL_NAME);
    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        instruccion + truncate(texto, MAX_CHARS_DIAGNOSTICO) + "\n</expediente_ocr>");
    return objectMapper.writeValueAsString(body);
  }

  private static final String[] CLAVES_DATOS_EXTRAIDOS = {
    "numero_juicio_coactivo",
    "numero_operacion",
    "nombre_deudor_principal_1",
    "cedula_deudor_principal_1",
    "correo_notificacion_1",
    "nombre_deudor_principal_2",
    "cedula_deudor_principal_2",
    "nombre_garante_solidario_1",
    "cedula_garante_solidario_1",
    "nombre_garante_solidario_2",
    "cedula_garante_solidario_2",
    "monto_deuda_total",
    "monto_credito_original",
    "monto_retencion",
    "monto_retencion_1",
    "monto_retencion_2",
    "monto_embargo_1",
    "monto_embargo_2",
    "monto_embargo_3",
    "fecha_liquidacion",
    "fecha_actuacion",
    "hora_actuacion",
    "fecha_providencia",
    "fecha_embargo",
    "fecha_orden_pago_inmediato",
    "fecha_escrito_presentado",
    "fecha_comprobante_pago",
    "ciudad_actuacion",
    "funcionario_coactiva",
    "abogado_secretario",
    "numero_resolucion_delegacion",
    "fecha_resolucion_delegacion",
    "nombre_gerente_general",
    "numero_oficio",
    "numero_oficio_retencion_1",
    "numero_oficio_retencion_2",
    "numero_cuenta_1",
    "numero_cuenta_2",
    "numero_cuenta_3",
    "numero_cuenta_destino",
    "numero_cuenta_embargada",
    "numero_cuenta_retencion_1",
    "numero_comprobante_pago",
    "institucion_financiera",
    "ruc_entidad_financiera",
    "entidad_financiera_destino",
    "tipo_cuenta_destino",
    "nombre_coactivado",
    "cedula_coactivado",
    "calidad_coactivado",
    "nombre_embargado",
    "cedula_embargado",
    "calidad_embargado",
    "nombre_depositario",
    "cedula_depositario",
    "titulo_depositario",
    "fecha_auto_designacion",
    "numero_zona",
    "objetivo_medida"
  };

  private ObjectNode schemaDiagnostico() {
    ObjectNode doc = objectMapper.createObjectNode();
    doc.put("type", "object");
    doc.put("additionalProperties", false);
    ObjectNode docProps = doc.putObject("properties");
    docProps.putObject("tipo").put("type", "string");
    docProps.putObject("foja_inicio").put("type", "integer").put("description", "0 si no consta.");
    docProps.putObject("foja_fin").put("type", "integer").put("description", "0 si no consta.");
    docProps.putObject("presente").put("type", "boolean");
    doc.putArray("required").add("tipo").add("presente");

    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode props = schema.putObject("properties");
    props.putObject("porcentaje_completitud").put("type", "integer").put("description", "0 a 100.");
    props
        .putObject("etapa_procesal_detectada")
        .put("type", "string")
        .put("description", "Igual a etapa_sugerida.codigo. Un solo código.");
    ObjectNode docs = props.putObject("documentos_identificados");
    docs.put("type", "array");
    docs.put("maxItems", 8);
    docs.set("items", doc);
    ObjectNode alertas = props.putObject("alertas_inconsistencias");
    alertas.put("type", "array");
    alertas.put("maxItems", 4);
    alertas.putObject("items").put("type", "string").put("maxLength", 180);
    ObjectNode datos = props.putObject("datos_extraidos");
    datos.put("type", "object");
    datos.put(
        "description",
        "Objeto con TODAS las claves listadas en el system prompt. Valor literal del OCR si consta; null si no consta. No omitas claves. No inventes valores.");
    ObjectNode datosProps = datos.putObject("properties");
    for (String clave : CLAVES_DATOS_EXTRAIDOS) {
      datosProps.putObject(clave).putArray("type").add("string").add("null");
    }
    datos.put("additionalProperties", true);
    props
        .putObject("siguiente_accion_sugerida")
        .put("type", "string")
        .put("maxLength", 200);
    props.set("etapa_sugerida", schemaEtapaSugerida());
    ObjectNode accionItem = objectMapper.createObjectNode();
    accionItem.put("type", "object");
    accionItem.put("additionalProperties", false);
    ObjectNode accionProps = accionItem.putObject("properties");
    accionProps.putObject("codigo_accion").put("type", "string");
    accionProps.putObject("titulo").put("type", "string").put("maxLength", 120);
    accionProps.putObject("descripcion").put("type", "string").put("maxLength", 180);
    accionProps.putObject("prioridad").put("type", "string");
    ObjectNode acciones = props.putObject("acciones_sugeridas");
    acciones.put("type", "array");
    acciones.put("maxItems", 2);
    acciones.set("items", accionItem);
    schema
        .putArray("required")
        .add("etapa_procesal_detectada")
        .add("etapa_sugerida")
        .add("siguiente_accion_sugerida");
    return schema;
  }

  /** Segunda llamada: solo etapa, justificación corta y una evidencia. Sin catálogo ni listas. */
  private ObjectNode schemaDiagnosticoBreve() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.putObject("properties").set("etapa_sugerida", schemaEtapaSugerida());
    schema.putArray("required").add("etapa_sugerida");
    return schema;
  }

  private ObjectNode schemaEtapaSugerida() {
    ObjectNode evidencia = objectMapper.createObjectNode();
    evidencia.put("type", "object");
    evidencia.put("additionalProperties", false);
    ObjectNode evProps = evidencia.putObject("properties");
    evProps
        .putObject("documento_origen")
        .put("type", "string")
        .put("description", "Título tal como consta. Vacío si no hay cita.");
    evProps
        .putObject("foja_pagina")
        .put("type", "integer")
        .put("description", "0 si no consta.");
    evProps
        .putObject("fecha_documento")
        .put("type", "string")
        .put("description", "Tal como consta, o vacío.");
    evProps
        .putObject("extracto_texto")
        .put("type", "string")
        .put("maxLength", 180)
        .put("description", "Cita literal de hasta 180 caracteres. Vacío si no consta. No la inventes.");
    ObjectNode etapa = objectMapper.createObjectNode();
    etapa.put("type", "object");
    etapa.put("additionalProperties", false);
    ObjectNode etapaProps = etapa.putObject("properties");
    etapaProps
        .putObject("codigo")
        .put("type", "string")
        .put("description", "Un solo código de etapa, el más probable.");
    etapaProps.putObject("nombre").put("type", "string");
    etapaProps
        .putObject("justificacion_juridica")
        .put("type", "string")
        .put("maxLength", 300)
        .put("description", "Máximo dos frases y 300 caracteres. Solo hechos del OCR.");
    etapaProps.set("evidencia", evidencia);
    etapa.putArray("required").add("codigo").add("justificacion_juridica");
    return etapa;
  }

  private String construirBodyCapturaBiess(String modelo, String texto) throws IOException {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (String field :
        new String[] {
          "monto_aprobado",
          "plazo_aprobado",
          "cuota_aprobada",
          "tasa_efectiva",
          "valor_reposicion",
          "porcentaje_valor_financiado"
        }) {
      props
          .putObject(field)
          .put("type", "string")
          .put(
              "description",
              "Valor de "
                  + field
                  + " tal como aparece en el texto, con unidades y separadores (ej. \"25 años\","
                  + " \"8,69 %\", \"85.000,50\"). Cadena vacía si no consta.");
      required.add(field);
    }
    props
        .putObject("apoderado")
        .put("type", "string")
        .put("description", "Nombre del apoderado especial del BIESS, o cadena vacía.");
    required.add("apoderado");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", 512);
    body.put("system", actos.capturaBiessPrompt());
    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", CAPTURA_BIESS_TOOL_NAME);
    tool.put(
        "description",
        "Registra las cifras de DATOS APROBADOS PARA DESEMBOLSO (monto, plazo, cuota, tasa,"
            + " valor de reposición, porcentaje financiado) y el apoderado BIESS.");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", CAPTURA_BIESS_TOOL_NAME);
    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        "<captura_biess_ocr>\n" + truncate(texto, MAX_CHARS_CAPTURA) + "\n</captura_biess_ocr>");
    return objectMapper.writeValueAsString(body);
  }

  private String construirBodyMinutaVivienda(
      String modelo, String texto, String productCode, String templateKind) throws IOException {
    ActoVariableCatalog.ActoVariableSchema schemaActo = actos.resolve(productCode, templateKind);
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("acto").put("type", "string").put("description", "Código del acto, sin cambiarlo.");
    ObjectNode variables = props.putObject("variables");
    variables.put("type", "object");
    ObjectNode varProps = variables.putObject("properties");
    ArrayNode requiredVars = variables.putArray("required");
    for (String field : schemaActo.camposIa()) {
      varProps
          .putObject(field)
          .put("type", "string")
          .put("description", "Valor en el OCR. Cadena vacía si no consta. No redactes cláusulas.");
      requiredVars.add(field);
    }
    schema.putArray("required").add("acto").add("variables");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelo);
    body.put("max_tokens", MAX_TOKENS_EXPEDIENTE);
    body.put("system", actos.systemPrompt(productCode, templateKind));

    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", MINUTA_VIVIENDA_TOOL_NAME);
    tool.put("description", "Registra solo las variables del acto " + schemaActo.codigo() + ".");
    tool.set("input_schema", schema);
    body.putObject("tool_choice").put("type", "tool").put("name", MINUTA_VIVIENDA_TOOL_NAME);

    ObjectNode msg = body.putArray("messages").addObject();
    msg.put("role", "user");
    msg.put(
        "content",
        "Extrae variables del acto "
            + schemaActo.codigo()
            + ". No redactes el documento.\n\n<expediente_ocr>\n"
            + truncate(texto, MAX_CHARS_OCR)
            + "\n</expediente_ocr>");

    return objectMapper.writeValueAsString(body);
  }

  private JsonNode leerToolInput(JsonNode root) {
    for (JsonNode block : root.path("content")) {
      if ("tool_use".equals(block.path("type").asText())) {
        JsonNode input = block.path("input");
        if (input.isObject()) {
          return input;
        }
      }
    }
    return null;
  }

  private HttpResponse<String> enviar(String body) throws IOException, InterruptedException {
    return enviar(body, TIMEOUT_DEFAULT);
  }

  protected HttpResponse<String> enviar(String body, Duration limite) throws IOException, InterruptedException {
    long inicio = System.nanoTime();
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(API_URL))
            .timeout(limite)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.net.http.HttpTimeoutException timeout) {
      throw new LlmTimeoutException(
          "Claude no respondió en " + limite.toSeconds() + " segundos.", timeout);
    }
    anotarUso(body, res.body(), (System.nanoTime() - inicio) / 1_000_000L);
    return res;
  }

  private void anotarUso(String requestBody, String responseBody, long ms) {
    try {
      JsonNode req = objectMapper.readTree(requestBody);
      JsonNode usage = objectMapper.readTree(responseBody).path("usage");
      int prompt = usage.path("input_tokens").asInt(0);
      int completion = usage.path("output_tokens").asInt(0);
      if (prompt == 0 && completion == 0) {
        return;
      }
      consumo.registrar(req.path("model").asText("claude"), prompt, completion, ms);
    } catch (RuntimeException | IOException ex) {
      LOG.debug("Sin usage de Claude: {}", ex.getMessage());
    }
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
