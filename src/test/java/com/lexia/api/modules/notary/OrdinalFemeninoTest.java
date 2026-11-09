package com.lexia.api.modules.notary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrdinalFemeninoTest {

  @ParameterizedTest
  @CsvSource({
    "1, PRIMERA",
    "10, DÉCIMA",
    "11, UNDÉCIMA",
    "12, DUODÉCIMA",
    "13, DÉCIMA TERCERA",
    "20, VIGÉSIMA",
    "21, VIGÉSIMA PRIMERA",
    "61, SEXAGÉSIMA PRIMERA",
    "99, NONAGÉSIMA NOVENA",
    "' 7 ', SÉPTIMA"
  })
  void convierteEnterosDe1A99(String numero, String esperado) {
    assertEquals(Optional.of(esperado), OrdinalFemenino.de(numero));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "100", "-3", "12A", "doce", ""})
  void fueraDeRangoONoNumericoQuedaVacio(String valor) {
    assertTrue(OrdinalFemenino.de(valor).isEmpty());
  }

  @org.junit.jupiter.api.Test
  void nuloQuedaVacio() {
    assertTrue(OrdinalFemenino.de(null).isEmpty());
  }
}
