package com.lexia.api.modules.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class FirmaEstudioValidacionTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private static AdminDtos.FirmaEstudio firma(String correo) {
    return new AdminDtos.FirmaEstudio("E", "A", "1", "099", correo, "Guayaquil", null);
  }

  @Test
  void correoInvalidoSeRechaza() {
    var errores = validator.validate(firma("no-es-correo"));
    assertEquals(1, errores.size());
    assertEquals("correo", errores.iterator().next().getPropertyPath().toString());
  }

  @Test
  void correoValidoOVacioPasa() {
    assertTrue(validator.validate(firma("firma@ejemplo.test")).isEmpty());
    assertTrue(validator.validate(firma("")).isEmpty());
    assertTrue(validator.validate(firma(null)).isEmpty());
  }
}
