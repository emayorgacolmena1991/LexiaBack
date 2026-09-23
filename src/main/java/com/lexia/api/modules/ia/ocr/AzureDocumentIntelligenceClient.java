package com.lexia.api.modules.ia.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Cliente Azure DI: semáforo (F0 ~2 TPS) + retry 429 con exponential backoff + jitter /
 * {@code Retry-After}.
 */
@Component
public class AzureDocumentIntelligenceClient {

  private static final Logger LOG = LoggerFactory.getLogger(AzureDocumentIntelligenceClient.class);
  private static final String API_VERSION = "2024-11-30";
  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final int MAX_POLLS = 40;
  private static final long POLL_MS = 1500;

  private final String azureUrl;
  private final String azureKey;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Semaphore concurrency;
  private final int maxRetries;
  private final long backoffBaseMs;
  private final long backoffMaxMs;
  private final long acquireTimeoutMs;

  public AzureDocumentIntelligenceClient(
      @Value("${azure.ocr.endpoint:}") String azureUrl,
      @Value("${azure.ocr.key:}") String azureKey,
      @Value("${azure.ocr.max-concurrent:1}") int maxConcurrent,
      @Value("${azure.ocr.max-retries:6}") int maxRetries,
      @Value("${azure.ocr.backoff-base-ms:1000}") long backoffBaseMs,
      @Value("${azure.ocr.backoff-max-ms:60000}") long backoffMaxMs,
      @Value("${azure.ocr.acquire-timeout-ms:180000}") long acquireTimeoutMs,
      ObjectMapper objectMapper) {
    this.azureUrl = trimSlash(azureUrl);
    this.azureKey = azureKey == null ? "" : azureKey.trim();
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    int permits = Math.max(1, maxConcurrent);
    this.concurrency = new Semaphore(permits, true);
    this.maxRetries = Math.max(0, maxRetries);
    this.backoffBaseMs = Math.max(100, backoffBaseMs);
    this.backoffMaxMs = Math.max(this.backoffBaseMs, backoffMaxMs);
    this.acquireTimeoutMs = Math.max(1000, acquireTimeoutMs);
  }

  public boolean isConfigured() {
    return StringUtils.hasText(azureUrl) && StringUtils.hasText(azureKey);
  }

  /**
   * Analyze + poll. Retiene 1 permiso del semáforo durante toda la operación (F0-safe).
   *
   * @return nodo {@code analyzeResult}
   */
  public JsonNode analyze(String modelId, byte[] bytes, String contentType) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("Archivo vacío para Azure DI.");
    }
    if (!isConfigured()) {
      throw new IllegalStateException(
          "AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT / AZURE_DOCUMENT_INTELLIGENCE_KEY no configurados.");
    }
    if (!StringUtils.hasText(modelId)) {
      throw new IllegalArgumentException("modelId Azure requerido.");
    }

    boolean acquired = false;
    try {
      acquired = concurrency.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new IllegalStateException(
            "Timeout esperando cupo Azure DI (max-concurrent="
                + concurrency.availablePermits()
                + ").");
      }

      String ct = StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
      String analyzeUrl =
          azureUrl
              + "/documentintelligence/documentModels/"
              + modelId
              + ":analyze?api-version="
              + API_VERSION;

      HttpRequest analyzeReq =
          HttpRequest.newBuilder()
              .uri(URI.create(analyzeUrl))
              .timeout(TIMEOUT)
              .header("Ocp-Apim-Subscription-Key", azureKey)
              .header("Content-Type", ct)
              .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
              .build();

      HttpResponse<String> analyzeRes = sendWithBackoff(analyzeReq, "analyze/" + modelId);
      if (analyzeRes.statusCode() != 202) {
        throw new IllegalStateException(
            "Azure DI analyze HTTP "
                + analyzeRes.statusCode()
                + ": "
                + truncate(analyzeRes.body()));
      }

      String operationLocation =
          analyzeRes.headers().firstValue("Operation-Location").orElse(null);
      if (!StringUtils.hasText(operationLocation)) {
        throw new IllegalStateException("Azure DI sin Operation-Location.");
      }

      for (int i = 0; i < MAX_POLLS; i++) {
        HttpRequest pollReq =
            HttpRequest.newBuilder()
                .uri(URI.create(operationLocation))
                .timeout(TIMEOUT)
                .header("Ocp-Apim-Subscription-Key", azureKey)
                .GET()
                .build();
        HttpResponse<String> pollRes = sendWithBackoff(pollReq, "poll/" + modelId);
        if (pollRes.statusCode() < 200 || pollRes.statusCode() >= 300) {
          throw new IllegalStateException(
              "Azure DI poll HTTP " + pollRes.statusCode() + ": " + truncate(pollRes.body()));
        }

        JsonNode root = objectMapper.readTree(pollRes.body());
        String status = textOrEmpty(root, "status").toLowerCase();
        if ("succeeded".equals(status)) {
          return root.path("analyzeResult");
        }
        if ("failed".equals(status)) {
          throw new IllegalStateException("Azure DI falló: " + truncate(pollRes.body()));
        }
        Thread.sleep(POLL_MS);
      }
      throw new IllegalStateException("Azure DI timeout esperando resultado.");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrumpido durante llamada Azure DI.", ie);
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("Error Azure DI: {}", e.getMessage());
      throw new IllegalStateException("Error llamando Azure Document Intelligence.", e);
    } finally {
      if (acquired) {
        concurrency.release();
      }
    }
  }

  HttpResponse<String> sendWithBackoff(HttpRequest request, String opLabel)
      throws Exception {
    HttpResponse<String> last = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      last = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (last.statusCode() != 429) {
        return last;
      }
      if (attempt == maxRetries) {
        break;
      }
      long delayMs = delayFor429(last, attempt);
      LOG.warn(
          "Azure DI 429 en {} (intento {}/{}). Reintento en {} ms",
          opLabel,
          attempt + 1,
          maxRetries,
          delayMs);
      Thread.sleep(delayMs);
    }
    throw new IllegalStateException(
        "Azure DI HTTP 429 tras "
            + maxRetries
            + " reintentos: "
            + truncate(last == null ? null : last.body()));
  }

  /** Retry-After si existe; si no, exponential backoff + jitter. */
  long delayFor429(HttpResponse<?> response, int attempt) {
    long fromHeader = parseRetryAfterMs(response);
    if (fromHeader > 0) {
      long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, backoffBaseMs / 4));
      return Math.min(backoffMaxMs, fromHeader + jitter);
    }
    long exp = backoffBaseMs * (1L << Math.min(attempt, 16));
    long capped = Math.min(backoffMaxMs, exp);
    long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, capped / 2));
    return Math.min(backoffMaxMs, capped / 2 + jitter);
  }

  private static long parseRetryAfterMs(HttpResponse<?> response) {
    if (response == null) {
      return -1;
    }
    String raw = response.headers().firstValue("Retry-After").orElse(null);
    if (!StringUtils.hasText(raw)) {
      return -1;
    }
    try {
      return Long.parseLong(raw.trim()) * 1000L;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String trimSlash(String url) {
    if (url == null) {
      return "";
    }
    String t = url.trim();
    while (t.endsWith("/")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  private static String textOrEmpty(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? "" : v.asText("");
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() > 300 ? body.substring(0, 300) + "…" : body;
  }
}
