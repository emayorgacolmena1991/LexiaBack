package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MotivoRechazoAdvanceTest {

  @Test
  void clasificaElTextoDeBloqueo() {
    assertEquals(
        MotivoRechazoAdvance.OBSERVACIONES_ABIERTAS,
        MotivoRechazoAdvance.desdeDetalle("Hay validaciones en estado «No cumple»."));
    assertEquals(
        MotivoRechazoAdvance.CAMPOS_FALTANTES,
        MotivoRechazoAdvance.desdeDetalle("El gate documentos debe estar en «Cumple»."));
    assertEquals(
        MotivoRechazoAdvance.ESTADO_INVALIDO,
        MotivoRechazoAdvance.desdeDetalle("El expediente no tiene etapa actual."));
  }
}
