package com.lexia.api.common.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final List<String> allowedNext;
  private final Map<String, Object> details;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiException(HttpStatus status, String code, String message, List<String> allowedNext) {
    this(status, code, message, allowedNext, Map.of());
  }

  /** {@code details} se agrega tal cual al cuerpo JSON de la respuesta de error. */
  public ApiException(
      HttpStatus status,
      String code,
      String message,
      List<String> allowedNext,
      Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.allowedNext = allowedNext == null ? List.of() : List.copyOf(allowedNext);
    this.details = details == null ? Map.of() : Map.copyOf(details);
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

  public Map<String, Object> getDetails() {
    return details;
  }
}
