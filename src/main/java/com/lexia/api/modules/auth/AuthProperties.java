package com.lexia.api.modules.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lexia.auth")
public class AuthProperties {

  private boolean enabled = true;
  private int sessionHours = 8;
  private int refreshDays = 7;
  private int challengeMinutes = 5;
  private int maxFailedAttempts = 5;
  private int lockoutMinutes = 30;
  private boolean cookieSecure = false;
  private String demoPassword = "Lexia-Demo-2026!";
  private String cryptoKey = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getSessionHours() {
    return sessionHours;
  }

  public void setSessionHours(int sessionHours) {
    this.sessionHours = sessionHours;
  }

  public int getRefreshDays() {
    return refreshDays;
  }

  public void setRefreshDays(int refreshDays) {
    this.refreshDays = refreshDays;
  }

  public int getChallengeMinutes() {
    return challengeMinutes;
  }

  public void setChallengeMinutes(int challengeMinutes) {
    this.challengeMinutes = challengeMinutes;
  }

  public int getMaxFailedAttempts() {
    return maxFailedAttempts;
  }

  public void setMaxFailedAttempts(int maxFailedAttempts) {
    this.maxFailedAttempts = maxFailedAttempts;
  }

  public int getLockoutMinutes() {
    return lockoutMinutes;
  }

  public void setLockoutMinutes(int lockoutMinutes) {
    this.lockoutMinutes = lockoutMinutes;
  }

  public boolean isCookieSecure() {
    return cookieSecure;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public String getDemoPassword() {
    return demoPassword;
  }

  public void setDemoPassword(String demoPassword) {
    this.demoPassword = demoPassword;
  }

  public String getCryptoKey() {
    return cryptoKey;
  }

  public void setCryptoKey(String cryptoKey) {
    this.cryptoKey = cryptoKey;
  }
}
