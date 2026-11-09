package com.lexia.api.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

  private static final String CEDULA = "1712345678";

  private final ApiExceptionHandler handler = new ApiExceptionHandler();
  private Logger logger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void capturarLog() {
    logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void soltarLog() {
    logger.detachAppender(logs);
  }

  @Test
  void indiceDeRemisionAbiertaResponde409SinValoresDeFila() {
    ResponseEntity<Map<String, String>> respuesta =
        handler.integrity(violacion("ux_notary_remision_abierta", CEDULA));

    assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
    assertEquals("El expediente ya tiene una remisión abierta.", respuesta.getBody().get("message"));
    String volcado = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
    assertFalse(volcado.contains(CEDULA));
    assertFalse(volcado.contains("Key"));
  }

  @Test
  void otroConstraintResponde409Generico() {
    ResponseEntity<Map<String, String>> respuesta = handler.integrity(violacion("uk_otro", CEDULA));

    assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
    assertEquals(
        "No fue posible completar la operación porque ya existe un registro relacionado.",
        respuesta.getBody().get("message"));
    String volcado = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
    assertFalse(volcado.contains(CEDULA));
  }

  private static DataIntegrityViolationException violacion(String constraint, String valor) {
    SQLException sql =
        new SQLException(
            "duplicate key value violates unique constraint \""
                + constraint
                + "\" Detail: Key (firm_legal_case_id)=("
                + valor
                + ")",
            "23505");
    return new DataIntegrityViolationException(
        "fallo", new ConstraintViolationException(sql.getMessage(), sql, constraint));
  }
}
