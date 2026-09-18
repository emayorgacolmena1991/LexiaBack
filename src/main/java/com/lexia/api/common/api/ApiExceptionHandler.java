package com.lexia.api.common.api;

import com.lexia.api.modules.auth.AuthException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
}
