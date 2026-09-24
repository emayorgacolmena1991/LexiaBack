package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OCR con Azure AI Document Intelligence ({@code prebuilt-read}).
 * No implementa {@link OcrService}: ese contrato devuelve {@code DatosExtraidosDTO} (Gemini).
 */
@Service
@EnableConfigurationProperties(AzureDocumentIntelligenceProperties.class)
public class AzureDocumentIntelligenceService {

  private static final Logger LOG = LoggerFactory.getLogger(AzureDocumentIntelligenceService.class);
  private static final String SUBSCRIPTION_HEADER = "Ocp-Apim-Subscription-Key";

  private final AzureDocumentIntelligenceProperties properties;
  private final ObjectMapper objectMapper;
  private final Gateway gateway;

  @Autowired
  public AzureDocumentIntelligenceService(
      AzureDocumentIntelligenceProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, new RestClientGateway(properties));
  }

  AzureDocumentIntelligenceService(
      AzureDocumentIntelligenceProperties properties, ObjectMapper objectMapper, Gateway gateway) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.gateway = gateway;
  }

  public boolean isConfigured() {
    return properties.isConfigured();
  }

  public OcrTextResult analyze(byte[] fileBytes, String mimeType) {
    if (!properties.isConfigured()) {
      throw new AzureDocumentIntelligenceException(
          "Azure Document Intelligence no está configurado. Define endpoint, key y lexia.azure.document-intelligence.enabled=true.");
    }
    if (fileBytes == null || fileBytes.length == 0) {
      throw new AzureDocumentIntelligenceException("Archivo vacío: no hay contenido para OCR.");
    }

    String contentType = resolveContentType(mimeType);
    URI analyzeUri = analyzeUri();
    LOG.info(
        "Azure Document Intelligence analyze model={} bytes={} contentType={}",
        properties.getModelId(),
        fileBytes.length,
        contentType);

    StartAnalyzeResponse start;
    try {
      start = gateway.startAnalyze(analyzeUri, properties.getKey(), fileBytes, contentType);
    } catch (RestClientResponseException ex) {
      throw httpFailure("inicio de análisis", ex);
    } catch (RuntimeException ex) {
      throw new AzureDocumentIntelligenceException(
          "No se pudo contactar Azure Document Intelligence: " + safeMessage(ex.getMessage()), ex);
    }

    if (start.statusCode() == 200 && StringUtils.hasText(start.body())) {
      return parseResult(start.body());
    }
    if (start.statusCode() != 202 || !StringUtils.hasText(start.operationLocation())) {
      throw new AzureDocumentIntelligenceException(
          "Azure Document Intelligence no devolvió Operation-Location (HTTP "
              + start.statusCode()
              + ").");
    }

    return pollUntilDone(start.operationLocation());
  }

  private OcrTextResult pollUntilDone(String operationLocation) {
    Instant deadline = Instant.now().plusMillis(properties.getTimeoutMs());
    URI operationUri = URI.create(operationLocation);
    String lastBody = "";

    while (Instant.now().isBefore(deadline)) {
      String body;
      try {
        body = gateway.getOperation(operationUri, properties.getKey());
      } catch (RestClientResponseException ex) {
        throw httpFailure("consulta de operación", ex);
      } catch (RuntimeException ex) {
        throw new AzureDocumentIntelligenceException(
            "Fallo al consultar el resultado OCR: " + safeMessage(ex.getMessage()), ex);
      }
      lastBody = body == null ? "" : body;
      String status = readStatus(lastBody);
      if ("succeeded".equals(status)) {
        return parseResult(lastBody);
      }
      if ("failed".equals(status)) {
        throw new AzureDocumentIntelligenceException(
            "Azure Document Intelligence falló el análisis: " + readError(lastBody));
      }
      sleep();
    }

    throw new AzureDocumentIntelligenceException(
        "Timeout ("
            + properties.getTimeoutMs()
            + " ms) esperando OCR de Azure Document Intelligence. Último estado: "
            + readStatus(lastBody)
            + ".");
  }

  OcrTextResult parseResult(String json) {
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode analyzeResult = root.path("analyzeResult");
      if (analyzeResult.isMissingNode() || analyzeResult.isNull()) {
        throw new AzureDocumentIntelligenceException(
            "Respuesta de Azure sin analyzeResult.");
      }
      String modelId =
          textOr(
              analyzeResult.path("modelId").asText(null),
              properties.getModelId());
      String content = analyzeResult.path("content").asText("");
      if (!StringUtils.hasText(content)) {
        content = joinLines(analyzeResult.path("pages"));
      }
      int pages =
          analyzeResult.path("pages").isArray() ? analyzeResult.path("pages").size() : 0;
      return new OcrTextResult(content == null ? "" : content, modelId, pages);
    } catch (AzureDocumentIntelligenceException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AzureDocumentIntelligenceException(
          "No se pudo interpretar la respuesta OCR de Azure.", ex);
    }
  }

  private URI analyzeUri() {
    String endpoint = properties.getEndpoint().replaceAll("/+$", "");
    String path =
        "/documentintelligence/documentModels/"
            + properties.getModelId()
            + ":analyze?api-version="
            + properties.getApiVersion();
    return URI.create(endpoint + path);
  }

  private AzureDocumentIntelligenceException httpFailure(
      String phase, RestClientResponseException ex) {
    int status = ex.getStatusCode().value();
    String detail = safeMessage(ex.getResponseBodyAsString());
    if (status == 401 || status == 403) {
      return new AzureDocumentIntelligenceException(
          "Azure Document Intelligence rechazó la autenticación (HTTP " + status + ").");
    }
    if (status == 429) {
      return new AzureDocumentIntelligenceException(
          "Azure Document Intelligence limitó la cuota (HTTP 429).");
    }
    return new AzureDocumentIntelligenceException(
        "Error en " + phase + " de Azure Document Intelligence (HTTP " + status + "): " + detail);
  }

  private String readStatus(String json) {
    try {
      if (!StringUtils.hasText(json)) {
        return "";
      }
      return objectMapper.readTree(json).path("status").asText("").trim().toLowerCase(Locale.ROOT);
    } catch (Exception ex) {
      return "";
    }
  }

  private String readError(String json) {
    try {
      JsonNode error = objectMapper.readTree(json).path("error");
      String message = error.path("message").asText("");
      return StringUtils.hasText(message) ? safeMessage(message) : "análisis fallido";
    } catch (Exception ex) {
      return "análisis fallido";
    }
  }

  private void sleep() {
    try {
      Thread.sleep(properties.getPollIntervalMs());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AzureDocumentIntelligenceException("OCR de Azure interrumpido.", ex);
    }
  }

  private String safeMessage(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "sin detalle";
    }
    String sanitized = raw;
    if (StringUtils.hasText(properties.getKey())) {
      sanitized = sanitized.replace(properties.getKey(), "***");
    }
    return sanitized.length() > 400 ? sanitized.substring(0, 400) : sanitized;
  }

  private static String resolveContentType(String mimeType) {
    if (!StringUtils.hasText(mimeType)) {
      return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
    return mimeType.trim();
  }

  private static String textOr(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
  }

  private static String joinLines(JsonNode pages) {
    if (pages == null || !pages.isArray()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode page : pages) {
      JsonNode pageLines = page.path("lines");
      if (!pageLines.isArray()) {
        continue;
      }
      for (JsonNode line : pageLines) {
        String content = line.path("content").asText("");
        if (StringUtils.hasText(content)) {
          lines.add(content);
        }
      }
    }
    return String.join("\n", lines);
  }

  interface Gateway {
    StartAnalyzeResponse startAnalyze(URI uri, String apiKey, byte[] body, String contentType);

    String getOperation(URI uri, String apiKey);
  }

  record StartAnalyzeResponse(int statusCode, String operationLocation, String body) {}

  static final class RestClientGateway implements Gateway {
    private final RestClient restClient;

    RestClientGateway(AzureDocumentIntelligenceProperties properties) {
      JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
      long readTimeout = Math.min(properties.getTimeoutMs(), 60_000);
      factory.setReadTimeout(Duration.ofMillis(readTimeout));
      this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public StartAnalyzeResponse startAnalyze(
        URI uri, String apiKey, byte[] body, String contentType) {
      MediaType mediaType = MediaType.parseMediaType(contentType);
      return restClient
          .post()
          .uri(uri)
          .header(SUBSCRIPTION_HEADER, apiKey)
          .contentType(mediaType)
          .body(body)
          .exchange(
              (request, response) -> {
                String location = response.getHeaders().getFirst("Operation-Location");
                String responseBody =
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = response.getStatusCode().value();
                if (status >= 400) {
                  throw new RestClientResponseException(
                      "Azure analyze HTTP " + status,
                      status,
                      response.getStatusCode().toString(),
                      response.getHeaders(),
                      responseBody.getBytes(StandardCharsets.UTF_8),
                      StandardCharsets.UTF_8);
                }
                return new StartAnalyzeResponse(status, location, responseBody);
              });
    }

    @Override
    public String getOperation(URI uri, String apiKey) {
      return restClient
          .get()
          .uri(uri)
          .header(SUBSCRIPTION_HEADER, apiKey)
          .retrieve()
          .body(String.class);
    }
  }
}
