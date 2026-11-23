package com.lexia.api.modules.ia.llm;

/** La API del modelo no respondió dentro del timeout de socket. El job no reintenta con sleep. */
public class LlmTimeoutException extends RuntimeException {

  public LlmTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }

  public static boolean es(Throwable error) {
    if (error instanceof LlmTimeoutException || error instanceof java.net.http.HttpTimeoutException) {
      return true;
    }
    String msg = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
    return msg.contains("timeout") || msg.contains("timed out");
  }
}
