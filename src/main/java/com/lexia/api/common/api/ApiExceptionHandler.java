package com.lexia.api.common.api;

import com.lexia.api.modules.auth.AuthException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<Map<String, String>> auth(AuthException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, String>> api(ApiException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException exception) {
    LOG.warn("JSON request inválido: {}", exception.getMostSpecificCause().getMessage());
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "BAD_JSON",
                "message",
                "El formato JSON enviado en la petición no es válido."));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(
                error -> {
                  if ("email".equals(error.getField())) {
                    return "Ingresa un correo electrónico válido.";
                  }
                  if ("password".equals(error.getField())) {
                    return "La contraseña debe tener al menos 8 caracteres.";
                  }
                  return "Revisa los datos ingresados.";
                })
            .orElse("Revisa los datos ingresados.");
    return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION", "message", message));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<Map<String, String>> optimisticLock(ObjectOptimisticLockingFailureException exception) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "code",
                "CONFLICT",
                "message",
                "La operación chocó con un cambio concurrente. Recarga e inténtalo de nuevo."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException exception) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "code",
                "CONFLICT",
                "message",
                "No fue posible completar la operación porque ya existe un registro relacionado."));
  }
}
