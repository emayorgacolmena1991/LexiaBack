package com.lexia.api.modules.notary;

import com.lexia.api.common.api.ApiException;
import org.springframework.http.HttpStatus;

/** Errores de dominio notarial con cuerpo JSON `{code,message}` vía ApiExceptionHandler. */
public final class NotaryBusinessException {

  private NotaryBusinessException() {}

  public static ApiException badRequest(String message) {
    return ApiException.badRequest(message);
  }

  public static ApiException notFound(String message) {
    return ApiException.notFound(message);
  }

  public static ApiException conflict(String message) {
    return ApiException.conflict(message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
  }
}
