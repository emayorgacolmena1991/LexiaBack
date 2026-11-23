package com.lexia.api.modules.expedientes.coactivas.expediente;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lexia.api.common.api.ApiException;
import org.junit.jupiter.api.Test;

class CoactivaLoteEvidenciaTest {

  @Test
  void exigeDocumentoYRazon() {
    assertDoesNotThrow(() -> CoactivaLoteEvidencia.exigir("Providencia.pdf", "Ratificación de medidas"));
    assertThrows(ApiException.class, () -> CoactivaLoteEvidencia.exigir("  ", "razón"));
    assertThrows(ApiException.class, () -> CoactivaLoteEvidencia.exigir("Providencia.pdf", null));
  }
}
