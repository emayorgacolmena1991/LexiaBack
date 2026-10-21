package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NumeroALetrasTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "85000.00     | ochenta y cinco mil",
        "85,000.00    | ochenta y cinco mil",
        "85.000,50    | ochenta y cinco mil con 50/100",
        "$ 85.000,50  | ochenta y cinco mil con 50/100",
        "USD 85000    | ochenta y cinco mil",
        "1000000      | un millón",
        "1.000.000,00 | un millón",
        "1500         | mil quinientos",
        "21           | veintiún",
        "0            | cero",
        "0,05         | cero con 05/100",
        "100          | cien",
        "101          | ciento un",
        "21000        | veintiún mil",
        "45999.9      | cuarenta y cinco mil novecientos noventa y nueve con 90/100",
        "2500000      | dos millones quinientos mil",
        "500          | quinientos"
      })
  void montoEnLetras(String cifra, String esperado) {
    assertEquals(esperado, NumeroALetras.monto(cifra));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "ochenta mil", "12abc34", "1.000.50", "85000.000", "-500", "$"})
  void textoInvalidoQuedaVacio(String cifra) {
    assertEquals("", NumeroALetras.monto(cifra));
  }

  @Test
  void parseoDistingueSeparadorDecimalDeMiles() {
    assertEquals(new BigDecimal("85000.00"), NumeroALetras.parsearMonto("85,000.00").orElseThrow());
    assertEquals(new BigDecimal("85000.50"), NumeroALetras.parsearMonto("85.000,50").orElseThrow());
    assertEquals(new BigDecimal("85000"), NumeroALetras.parsearMonto("85.000").orElseThrow());
    assertTrue(NumeroALetras.parsearMonto(null).isEmpty());
  }
}
