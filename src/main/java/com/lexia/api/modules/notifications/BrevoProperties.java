package com.lexia.api.modules.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lexia.brevo")
public class BrevoProperties {

  private boolean enabled = false;
  private String apiKey = "";
  private String senderEmail = "noreply@lexia.app";
  private String senderName = "LEXIA";
  private String frontendUrl = "http://localhost:4200";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getSenderEmail() {
    return senderEmail;
  }

  public void setSenderEmail(String senderEmail) {
    this.senderEmail = senderEmail;
  }

  public String getSenderName() {
    return senderName;
  }

  public void setSenderName(String senderName) {
    this.senderName = senderName;
  }

  public String getFrontendUrl() {
    return frontendUrl;
  }

  public void setFrontendUrl(String frontendUrl) {
    this.frontendUrl = frontendUrl;
  }
}
