package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.lexia.api.modules.ia.gemini.GeminiJsonSanitizer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GeminiOcrServiceImpl implements OcrService {

  private static final Logger LOG = LoggerFactory.getLogger(GeminiOcrServiceImpl.class);

  private static final List<String> MODELOS =
      List.of("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest");

  private static final String PROMPT =
      "Extrae los datos más relevantes del documento. "
          + "Responde ÚNICAMENTE con JSON: "
          + "{\"tipoDocumento\":\"...\",\"resumen\":\"...\",\"datosClave\":{...}}. "
          + "datosClave = pares clave-valor importantes (nombres, ids, fechas, montos, etc.). "
          + "Sin markdown ni texto fuera del JSON.";

  private final String apiKey;
  private final ObjectMapper objectMapper;

  public GeminiOcrServiceImpl(
      @Value("${gemini.api.key:}") String apiKey, ObjectMapper objectMapper) {
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
  }

  @Override
  public ExpedienteDtos.DatosExtraidosDTO analizarDocumento(byte[] fileBytes, String mimeType) {
    if (!StringUtils.hasText(apiKey)) {
      throw new IllegalStateException("GEMINI_API_KEY / gemini.api.key no configurada.");
    }
    if (fileBytes == null || fileBytes.length == 0) {
      throw new IllegalArgumentException("Archivo vacío.");
    }

    String resolvedMime = StringUtils.hasText(mimeType) ? mimeType : "application/pdf";

    // PDF/imagen directo a Gemini: sin conversión local a imagen.
    Content content =
        Content.fromParts(Part.fromBytes(fileBytes, resolvedMime), Part.fromText(PROMPT));

    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseMimeType("application/json")
            .responseSchema(Schema.fromJson(GeminiSchemas.JSON_SCHEMA))
            .temperature(0.1f)
            .build();

    try (Client client = Client.builder().apiKey(apiKey).build()) {
      for (String modelo : MODELOS) {
        for (int intento = 0; intento < 2; intento++) {
          try {
            GenerateContentResponse response =
                client.models.generateContent(modelo, content, config);
            String text = response.text();
            if (StringUtils.hasText(text)) {
              String jsonLimpio = GeminiJsonSanitizer.limpiar(text);
              return objectMapper.readValue(jsonLimpio, ExpedienteDtos.DatosExtraidosDTO.class);
            }
          } catch (Exception e) {
            LOG.warn(
                "Fallo Gemini modelo={} intento={}: {}",
                modelo,
                intento + 1,
                e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("503") && intento == 0) {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrumpido durante reintento Gemini.", interrupted);
              }
            }
          }
        }
      }
    }

    throw new IllegalStateException("Error en procesamiento de documento con Gemini.");
  }
}
