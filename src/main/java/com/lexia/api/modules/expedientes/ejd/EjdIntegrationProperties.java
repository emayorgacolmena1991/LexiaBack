package com.lexia.api.modules.expedientes.ejd;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "lexia.integrations.ejd")
public class EjdIntegrationProperties {

  /** When true, enabled connectors complete stub calls as SUCCEEDED in-process. */
  private boolean stubLive = true;

  /** Optional HTTP gateway for institutional connectors (QUIPUX, NOTARIA, …). */
  private String liveBaseUrl = "";

  private final Worker worker = new Worker();

  public boolean isStubLive() {
    return stubLive;
  }

  public void setStubLive(boolean stubLive) {
    this.stubLive = stubLive;
  }

  public String getLiveBaseUrl() {
    return liveBaseUrl;
  }

  public void setLiveBaseUrl(String liveBaseUrl) {
    this.liveBaseUrl = liveBaseUrl == null ? "" : liveBaseUrl;
  }

  @NestedConfigurationProperty
  public Worker getWorker() {
    return worker;
  }

  public static class Worker {
    private long pollIntervalMs = 5000;
    private int batchSize = 20;

    public long getPollIntervalMs() {
      return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
      this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }
  }
}
