package com.lexia.api.modules.expedientes.ejd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdConnectorLiveClient {

  private static final Logger LOG = LoggerFactory.getLogger(EjdConnectorLiveClient.class);

  private final EjdIntegrationProperties properties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public EjdConnectorLiveClient(EjdIntegrationProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.restClient = RestClient.builder().build();
  }

  public LiveInvokeResult invoke(EjdConnectorOutboxPayload payload) {
    String baseUrl = properties.getLiveBaseUrl() == null ? "" : properties.getLiveBaseUrl().trim();
    if (baseUrl.isEmpty()) {
      return LiveInvokeResult.ok(simulatedResponse(payload));
    }
    String connector = payload.connector().trim().toLowerCase(Locale.ROOT);
    String url = baseUrl.replaceAll("/+$", "") + "/connectors/" + connector + "/invoke";
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("caseId", payload.caseId());
    body.put("caseCode", payload.caseCode());
    body.put("stageCode", payload.stageCode());
    body.put("trigger", payload.trigger());
    body.put("integrationCallId", payload.integrationCallId());
    try {
      String response =
          restClient
              .post()
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(String.class);
      return LiveInvokeResult.ok(response == null ? "{}" : response);
    } catch (RestClientResponseException ex) {
      LOG.warn(
          "Connector {} HTTP {} for case {}",
          payload.connector(),
          ex.getStatusCode().value(),
          payload.caseCode());
      return LiveInvokeResult.failed(
          "HTTP " + ex.getStatusCode().value() + ": " + truncate(ex.getResponseBodyAsString()));
    } catch (RuntimeException ex) {
      LOG.warn("Connector {} invoke failed for case {}", payload.connector(), payload.caseCode(), ex);
      return LiveInvokeResult.failed(truncate(ex.getMessage()));
    }
  }

  private String simulatedResponse(EjdConnectorOutboxPayload payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("processedBy", "outbox-worker");
    body.put("simulated", true);
    body.put("connector", payload.connector());
    body.put("stage", payload.stageCode());
    body.put("caseCode", payload.caseCode());
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException ex) {
      return body.toString();
    }
  }

  private static String truncate(String value) {
    if (value == null || value.isBlank()) {
      return "Connector invoke failed.";
    }
    return value.length() > 512 ? value.substring(0, 512) : value;
  }

  public record LiveInvokeResult(boolean success, String responseBody) {
    static LiveInvokeResult ok(String body) {
      return new LiveInvokeResult(true, body);
    }

    static LiveInvokeResult failed(String error) {
      return new LiveInvokeResult(false, error);
    }
  }
}
