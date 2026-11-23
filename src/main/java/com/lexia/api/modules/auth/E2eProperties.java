package com.lexia.api.modules.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lexia.e2e")
public class E2eProperties {

  private boolean enabled = false;
  /**
   * Con {@code lexia.e2e.enabled=true}, omite verificación de contraseña (y MFA en login UI) para
   * correos en {@code lexia.auth.bootstrap-emails}. Solo desarrollo/CI.
   */
  private boolean trustBootstrapEmails = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isTrustBootstrapEmails() {
    return trustBootstrapEmails;
  }

  public void setTrustBootstrapEmails(boolean trustBootstrapEmails) {
    this.trustBootstrapEmails = trustBootstrapEmails;
  }
}
