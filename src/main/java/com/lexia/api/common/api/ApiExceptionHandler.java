package com.lexia.api.common.api;

import com.lexia.api.modules.auth.AuthException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<Map<String, String>> auth(AuthException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "VALIDATION", "message", "Revisa los datos ingresados."));
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
