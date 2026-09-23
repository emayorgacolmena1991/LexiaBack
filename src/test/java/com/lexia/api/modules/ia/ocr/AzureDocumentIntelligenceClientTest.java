package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class AzureDocumentIntelligenceClientTest {

  @Test
  void delayUsaRetryAfterCuandoExiste() {
    AzureDocumentIntelligenceClient client =
        new AzureDocumentIntelligenceClient(
            "https://example.cognitiveservices.azure.com",
            "key",
            1,
            6,
            1000,
            60000,
            180000,
            new ObjectMapper());

    HttpResponse<String> res = fake429("5");
    long delay = client.delayFor429(res, 0);
    assertTrue(delay >= 5000 && delay < 6000, "delay=" + delay);
  }

  @Test
  void delayExponentialConJitterSinHeader() {
    AzureDocumentIntelligenceClient client =
        new AzureDocumentIntelligenceClient(
            "https://example.cognitiveservices.azure.com",
            "key",
            1,
            6,
            1000,
            60000,
            180000,
            new ObjectMapper());

    HttpResponse<String> res = fake429(null);
    long delay = client.delayFor429(res, 2);
    // base 1000 * 2^2 = 4000 → capped/2 + jitter ∈ [2000, 4000)
    assertTrue(delay >= 2000 && delay < 4000, "delay=" + delay);
  }

  private static HttpResponse<String> fake429(String retryAfter) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 429;
      }

      @Override
      public HttpRequest request() {
        return HttpRequest.newBuilder().uri(URI.create("https://example/")).GET().build();
      }

      @Override
      public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpHeaders headers() {
        if (retryAfter == null) {
          return HttpHeaders.of(Map.of(), (a, b) -> true);
        }
        return HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (a, b) -> true);
      }

      @Override
      public String body() {
        return "{\"error\":\"rate limit\"}";
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        return URI.create("https://example/");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }
}
