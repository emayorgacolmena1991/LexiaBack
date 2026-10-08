package com.lexia.api.modules.expedientes.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "lexia.azure.document-intelligence")
public class AzureDocumentIntelligenceProperties {

  /**
   * Kill-switch opcional. Con endpoint/key vacíos la integración sigue inactiva
   * aunque enabled=true; así Lexia arranca sin Azure.
   */
  private boolean enabled = true;

  private String endpoint = "";

  private String key = "";

  private String apiVersion = "2024-11-30";

  private String modelId = "prebuilt-read";

  private long timeoutMs = 120_000;

  private long pollIntervalMs = 1_000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint == null ? "" : endpoint.trim();
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key == null ? "" : key.trim();
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = StringUtils.hasText(apiVersion) ? apiVersion.trim() : "2024-11-30";
  }

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String modelId) {
    this.modelId = StringUtils.hasText(modelId) ? modelId.trim() : "prebuilt-read";
  }

  public long getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(long timeoutMs) {
    this.timeoutMs = Math.max(1_000, timeoutMs);
  }

  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = Math.max(200, pollIntervalMs);
  }

  public boolean isConfigured() {
    return enabled && StringUtils.hasText(endpoint) && StringUtils.hasText(key);
  }
}
