package com.lexia.api.modules.auth;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public AuthException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static AuthException unauthorized(String message) {
    return new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
  }

  public static AuthException locked(String message) {
    return new AuthException(HttpStatus.LOCKED, "LOCKED", message);
  }

  public static AuthException tooMany(String message) {
    return new AuthException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
  }

  public static AuthException conflict(String message) {
    return new AuthException(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  public static AuthException badRequest(String message) {
    return new AuthException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
