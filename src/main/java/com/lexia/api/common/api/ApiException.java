package com.lexia.api.common.api;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final List<String> allowedNext;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiException(HttpStatus status, String code, String message, List<String> allowedNext) {
    super(message);
    this.status = status;
    this.code = code;
    this.allowedNext = allowedNext == null ? List.of() : List.copyOf(allowedNext);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public List<String> getAllowedNext() {
    return allowedNext;
  }
}
