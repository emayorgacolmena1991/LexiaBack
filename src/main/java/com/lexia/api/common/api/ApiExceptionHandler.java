package com.lexia.api.common.api;

import com.lexia.api.modules.auth.AuthException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  static final String INDICE_REMISION_ABIERTA = "ux_notary_remision_abierta";
  private static final String MENSAJE_REMISION_ABIERTA =
      "El expediente ya tiene una remisión abierta.";
  private static final String MENSAJE_INTEGRIDAD =
      "No fue posible completar la operación porque ya existe un registro relacionado.";
  private static final Pattern NOMBRE_CONSTRAINT = Pattern.compile("[A-Za-z0-9_.]+");

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<Map<String, String>> auth(AuthException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> api(ApiException exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", exception.getCode());
    body.put("message", exception.getMessage());
    if (!exception.getAllowedNext().isEmpty()) {
      body.put("allowedNext", exception.getAllowedNext());
    }
    exception.getDetails().forEach(body::putIfAbsent);
    return ResponseEntity.status(exception.getStatus()).body(body);
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
    String constraint = constraintName(exception);
    LOG.warn("Integridad de datos: constraint={}", constraint == null ? "desconocido" : constraint);
    String message =
        INDICE_REMISION_ABIERTA.equals(constraint) ? MENSAJE_REMISION_ABIERTA : MENSAJE_INTEGRIDAD;
    return ResponseEntity.status(409).body(Map.of("code", "CONFLICT", "message", message));
  }

  /** Nombre del constraint, o el índice de remisión abierta si solo aparece en el mensaje. */
  private static String constraintName(Throwable error) {
    String porMensaje = null;
    Throwable actual = error;
    while (actual != null) {
      if (actual instanceof ConstraintViolationException violation) {
        String nombre = violation.getConstraintName();
        if (nombre != null && NOMBRE_CONSTRAINT.matcher(nombre).matches()) {
          return nombre;
        }
      }
      String message = actual.getMessage();
      if (message != null && message.contains(INDICE_REMISION_ABIERTA)) {
        porMensaje = INDICE_REMISION_ABIERTA;
      }
      actual = actual.getCause();
    }
    return porMensaje;
  }

  @ExceptionHandler(JpaSystemException.class)
  public ResponseEntity<Map<String, String>> jpa(JpaSystemException exception) {
    LOG.error("JPA error", exception);
    String detail = exception.getMostSpecificCause().getMessage();
    return ResponseEntity.internalServerError()
        .body(
            Map.of(
                "code",
                "INTERNAL_ERROR",
                "message",
                detail != null && !detail.isBlank() ? detail : "Error de persistencia."));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
    LOG.error("Unhandled API error", exception);
    return ResponseEntity.internalServerError()
        .body(Map.of("code", "INTERNAL_ERROR", "message", "Error interno del servidor."));
  }
}
