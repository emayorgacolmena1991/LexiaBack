package com.lexia.api.modules.ia.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Extracción dinámica con Gemini. Reintenta modelos free-tier con backoff ante 429/503.
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiAnalysisService implements AnalisisDocumentoService {

  private static final Logger LOG = LoggerFactory.getLogger(GeminiAnalysisService.class);

  /** Solo modelos con free tier (sin 2.5-pro: rate limit más agresivo / a veces pago). */
  private static final List<String> MODELOS_FREE =
      List.of(
          "gemini-flash-latest",
          "gemini-flash-lite-latest"
          );

  private static final int MAX_INTENTOS_POR_MODELO = 4;
  private static final long BACKOFF_BASE_MS = 1500L;

  private final String apiKey;
  private final ObjectMapper objectMapper;

  public GeminiAnalysisService(
      @Value("${gemini.api.key:}") String apiKey, ObjectMapper objectMapper) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean isConfigured() {
    return StringUtils.hasText(apiKey);
  }

  @Override
  public ExtraccionDocumento extraerDatosClave(String textoOcr, String tipoDocumento) {
    String tipo = StringUtils.hasText(tipoDocumento) ? tipoDocumento : "DOCUMENTO";
    String texto = textoOcr == null ? "" : textoOcr;

    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionDocumento.error("Sin texto OCR para extraer datos.");
    }
    if (!isConfigured()) {
      return ExtraccionDocumento.error("GEMINI_API_KEY no configurada.");
    }

    String prompt =
        "Analiza el siguiente texto OCR perteneciente a un documento de tipo '"
            + tipo
            + "'.\n"
            + "Identifica y extrae los datos más relevantes e importantes del documento.\n\n"
            + "REGLAS DE SALIDA:\n"
            + "1. Responde ÚNICAMENTE con un objeto JSON válido.\n"
            + "2. Estructura el JSON con los siguientes campos:\n"
            + "   - \"tipoDocumento\": Nombre exacto o identificado del documento.\n"
            + "   - \"resumen\": Una breve descripción de 1 a 2 oraciones del contenido del documento.\n"
            + "   - \"datosClave\": Un objeto JSON con los pares clave-valor más importantes "
            + "encontrados (ej: nombres, identificaciones, fechas, montos, números de registro, etc.).\n"
            + "3. No agregues comillas triples de markdown (```json), ni explicaciones adicionales.\n\n"
            + "TEXTO OCR:\n"
            + truncate(texto, 120_000);

    Content content = Content.fromParts(Part.fromText(prompt));
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .temperature(0.1f)
            .build();

    String ultimoDiagnostico = "sin respuesta";

    try (Client client = Client.builder().apiKey(apiKey).build()) {
      for (String modelo : MODELOS_FREE) {
        for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
          try {
            LOG.info(
                "Gemini free: modelo={} intento={}/{}", modelo, intento, MAX_INTENTOS_POR_MODELO);
            GenerateContentResponse response =
                client.models.generateContent(modelo, content, config);
            String raw = response.text();

            if (!StringUtils.hasText(raw)) {
              ultimoDiagnostico = "modelo=" + modelo + " respuesta vacía";
              LOG.warn(ultimoDiagnostico);
              sleepBackoff(intento, false);
              continue;
            }

            String jsonLimpio = GeminiJsonSanitizer.limpiar(raw);
            try {
              DatosExtraidosDTO datos =
                  objectMapper.readValue(jsonLimpio, DatosExtraidosDTO.class);
              LOG.info("Gemini OK con modelo={}", modelo);
              return ExtraccionDocumento.fromDatos(datos);
            } catch (Exception parseEx) {
              ultimoDiagnostico =
                  "modelo=" + modelo + " JSON inválido: " + parseEx.getMessage();
              LOG.warn("{} | preview={}", ultimoDiagnostico, truncate(raw, 200));
              // Reintentar mismo modelo: a veces el free tier recorta la salida
              sleepBackoff(intento, false);
            }
          } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String kind = classifyError(msg);
            ultimoDiagnostico = "modelo=" + modelo + " " + kind + ": " + truncate(msg, 180);
            LOG.warn("Fallo Gemini {}: {}", kind, ultimoDiagnostico);

            if ("NOT_FOUND".equals(kind)) {
              // Modelo no existe en este proyecto → siguiente modelo
              break;
            }
            if ("RATE_LIMIT".equals(kind) || "UNAVAILABLE".equals(kind)) {
              sleepBackoff(intento, true);
              continue;
            }
            sleepBackoff(intento, false);
          }
        }
      }
    }

    return ExtraccionDocumento.error(
        "No se pudieron extraer datos con Gemini (free). Último: " + ultimoDiagnostico);
  }

  private static String classifyError(String msg) {
    String m = msg.toLowerCase(Locale.ROOT);
    if (m.contains("404") || m.contains("not_found") || m.contains("not found")) {
      return "NOT_FOUND";
    }
    if (m.contains("429")
        || m.contains("resource_exhausted")
        || m.contains("rate")
        || m.contains("quota")
        || m.contains("exceeded")) {
      return "RATE_LIMIT";
    }
    if (m.contains("503") || m.contains("unavailable") || m.contains("high demand")) {
      return "UNAVAILABLE";
    }
    return "OTHER";
  }

  private static void sleepBackoff(int intento, boolean rateLimit) {
    long ms = BACKOFF_BASE_MS * intento;
    if (rateLimit) {
      ms = Math.min(12_000L, ms * 2);
    }
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
