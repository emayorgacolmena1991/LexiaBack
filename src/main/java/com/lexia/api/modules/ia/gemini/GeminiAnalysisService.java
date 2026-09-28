package com.lexia.api.modules.ia.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Análisis de expediente con Gemini: una sola llamada por expediente (extracción por documento +
 * consolidado + dictamen). Reintenta modelos free-tier con backoff ante 429/503.
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
  private static final int MAX_OUTPUT_TOKENS = 8192;
  private static final long BACKOFF_BASE_MS = 1500L;

  private static final String FORMATO_JSON =
      """

      Responde ÚNICAMENTE con un objeto JSON válido, sin markdown:
      {"documentosExtraidos": [{"documentoId": "...", "tipoDocumento": "...", "resumen": "...",
        "datosClave": {}}],
       "datosConsolidados": {"comprador": {"nombres": "", "cedula": "", "estadoCivil": ""},
        "vendedor": {"nombres": "", "cedula": "", "estadoCivil": ""},
        "inmueble": {"claveCatastral": "", "linderos": "", "avaluo": 0}},
       "dictamen": {"estado": "APPROVED|WITH_OBSERVATIONS|REJECTED", "resumen": "",
        "observaciones": [{"codigo": "", "severidad": "HIGH|MEDIUM|LOW", "mensaje": ""}]}}
      """;

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
  public ExtraccionExpedienteCompleto procesarExpedienteCompleto(
      String ocrMarcado, String systemPrompt) {
    String texto = ocrMarcado == null ? "" : ocrMarcado;
    if (!StringUtils.hasText(texto.trim())) {
      return ExtraccionExpedienteCompleto.error("Sin texto OCR para procesar el expediente.");
    }
    if (!isConfigured()) {
      return ExtraccionExpedienteCompleto.error("GEMINI_API_KEY no configurada.");
    }
    String system =
        (StringUtils.hasText(systemPrompt) ? systemPrompt : "Procesa el expediente notarial.")
            + ProcesarExpedienteCompletoPayload.REGLAS_SALIDA
            + FORMATO_JSON;
    String marcado =
        texto.contains("<expediente_ocr>")
            ? texto
            : "<expediente_ocr>\n" + texto + "\n</expediente_ocr>";
    Content content =
        Content.fromParts(
            Part.fromText(system), Part.fromText(truncate(marcado, 120_000)));
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .temperature(0.1f)
            .maxOutputTokens(MAX_OUTPUT_TOKENS)
            .build();

    String ultimoDiagnostico = "sin respuesta";
    try (Client client = Client.builder().apiKey(apiKey).build()) {
      for (String modelo : MODELOS_FREE) {
        for (int intento = 1; intento <= MAX_INTENTOS_POR_MODELO; intento++) {
          try {
            LOG.info(
                "Gemini expediente completo: modelo={} intento={}/{}",
                modelo,
                intento,
                MAX_INTENTOS_POR_MODELO);
            GenerateContentResponse response =
                client.models.generateContent(modelo, content, config);
            String raw = response.text();
            if (!StringUtils.hasText(raw)) {
              ultimoDiagnostico = "modelo=" + modelo + " respuesta vacía";
              sleepBackoff(intento, false);
              continue;
            }
            ProcesarExpedienteCompletoPayload payload =
                objectMapper.readValue(
                    GeminiJsonSanitizer.limpiar(raw), ProcesarExpedienteCompletoPayload.class);
            if (payload != null && payload.datosExtraidos() != null && payload.dictamen() != null) {
              LOG.info(
                  "Gemini expediente completo OK modelo={} documentos={}",
                  modelo,
                  payload.documentosExtraidos().size());
              return ExtraccionExpedienteCompleto.ok(payload);
            }
            ultimoDiagnostico = "modelo=" + modelo + " JSON sin datosConsolidados/dictamen";
            sleepBackoff(intento, false);
          } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String kind = classifyError(msg);
            ultimoDiagnostico = "modelo=" + modelo + " " + kind + ": " + truncate(msg, 180);
            LOG.warn("Fallo Gemini expediente completo {}: {}", kind, ultimoDiagnostico);
            if ("NOT_FOUND".equals(kind)) {
              break;
            }
            sleepBackoff(intento, "RATE_LIMIT".equals(kind) || "UNAVAILABLE".equals(kind));
          }
        }
      }
    }
    return ExtraccionExpedienteCompleto.error(
        "No se pudo procesar el expediente con Gemini. Último: " + ultimoDiagnostico);
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
