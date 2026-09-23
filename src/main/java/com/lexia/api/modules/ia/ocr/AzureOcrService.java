package com.lexia.api.modules.ia.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Módulo 1: Azure Document Intelligence — solo texto plano OCR. */
@Service
public class AzureOcrService {

  private static final Logger LOG = LoggerFactory.getLogger(AzureOcrService.class);
  private static final String API_VERSION = "2024-11-30";
  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final int MAX_POLLS = 40;
  private static final long POLL_MS = 1500;

  private final String azureUrl;
  private final String azureKey;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AzureOcrService(
      @Value("${azure.ocr.endpoint:}") String azureUrl,
      @Value("${azure.ocr.key:}") String azureKey,
      ObjectMapper objectMapper) {
    this.azureUrl = trimSlash(azureUrl);
    this.azureKey = azureKey == null ? "" : azureKey.trim();
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  }

  public boolean isConfigured() {
    return StringUtils.hasText(azureUrl) && StringUtils.hasText(azureKey);
  }

  /** Extrae solo texto plano del PDF/imagen. */
  public String extraerTexto(byte[] bytesArchivo, String mimeType) {
    if (bytesArchivo == null || bytesArchivo.length == 0) {
      throw new IllegalArgumentException("Archivo vacío para OCR Azure.");
    }
    if (!isConfigured()) {
      throw new IllegalStateException(
          "AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT / AZURE_DOCUMENT_INTELLIGENCE_KEY no configurados.");
    }

    String contentType =
        StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream";
    String analyzeUrl =
        azureUrl
            + "/documentintelligence/documentModels/prebuilt-read:analyze?api-version="
            + API_VERSION;

    try {
      HttpRequest analyzeReq =
          HttpRequest.newBuilder()
              .uri(URI.create(analyzeUrl))
              .timeout(TIMEOUT)
              .header("Ocp-Apim-Subscription-Key", azureKey)
              .header("Content-Type", contentType)
              .POST(HttpRequest.BodyPublishers.ofByteArray(bytesArchivo))
              .build();

      HttpResponse<String> analyzeRes =
          httpClient.send(analyzeReq, HttpResponse.BodyHandlers.ofString());
      if (analyzeRes.statusCode() != 202) {
        throw new IllegalStateException(
            "Azure OCR analyze HTTP " + analyzeRes.statusCode() + ": " + truncate(analyzeRes.body()));
      }

      String operationLocation =
          analyzeRes.headers().firstValue("Operation-Location").orElse(null);
      if (!StringUtils.hasText(operationLocation)) {
        throw new IllegalStateException("Azure OCR sin Operation-Location.");
      }

      for (int i = 0; i < MAX_POLLS; i++) {
        HttpRequest pollReq =
            HttpRequest.newBuilder()
                .uri(URI.create(operationLocation))
                .timeout(TIMEOUT)
                .header("Ocp-Apim-Subscription-Key", azureKey)
                .GET()
                .build();
        HttpResponse<String> pollRes =
            httpClient.send(pollReq, HttpResponse.BodyHandlers.ofString());
        if (pollRes.statusCode() < 200 || pollRes.statusCode() >= 300) {
          throw new IllegalStateException(
              "Azure OCR poll HTTP " + pollRes.statusCode() + ": " + truncate(pollRes.body()));
        }

        JsonNode root = objectMapper.readTree(pollRes.body());
        String status = textOrEmpty(root, "status").toLowerCase();
        if ("succeeded".equals(status)) {
          JsonNode content = root.path("analyzeResult").path("content");
          if (content.isMissingNode() || content.isNull()) {
            return "";
          }
          return content.asText("");
        }
        if ("failed".equals(status)) {
          throw new IllegalStateException("Azure OCR falló: " + truncate(pollRes.body()));
        }
        Thread.sleep(POLL_MS);
      }
      throw new IllegalStateException("Azure OCR timeout esperando resultado.");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrumpido durante OCR Azure.", ie);
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("Error Azure OCR: {}", e.getMessage());
      throw new IllegalStateException("Error llamando Azure Document Intelligence.", e);
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
