package com.lexia.api.modules.expedientes.requisitos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VigenciaFechaTest {

  @Test
  void diaMayorQueDoceNoEsAmbiguo() {
    var f = VigenciaDocumentoService.leerFecha("15/03/2024");
    assertEquals(LocalDate.of(2024, 3, 15), f.valor());
    assertFalse(f.ambigua());
  }

  @Test
  void segundoNumeroMayorQueDoceSeLeeComoMesDia() {
    var f = VigenciaDocumentoService.leerFecha("03/15/2024");
    assertEquals(LocalDate.of(2024, 3, 15), f.valor());
    assertFalse(f.ambigua());
  }

  @Test
  void ambosMenoresODoceYDistintosEsAmbigua() {
    var f = VigenciaDocumentoService.leerFecha("05/03/2024");
    assertEquals(LocalDate.of(2024, 3, 5), f.valor());
    assertTrue(f.ambigua());
  }

  @Test
  void mismoDiaYMesNoEsAmbiguo() {
    assertFalse(VigenciaDocumentoService.leerFecha("05/05/2024").ambigua());
  }

  @Test
  void mesEnLetrasEIsoNoSonAmbiguos() {
    var texto = VigenciaDocumentoService.leerFecha("5 de marzo de 2024");
    assertEquals(LocalDate.of(2024, 3, 5), texto.valor());
    assertFalse(texto.ambigua());
    var iso = VigenciaDocumentoService.leerFecha("2024-03-05");
    assertEquals(LocalDate.of(2024, 3, 5), iso.valor());
    assertFalse(iso.ambigua());
  }

  @Test
  void fechaImposibleOTextoSinFecha() {
    assertNull(VigenciaDocumentoService.leerFecha("31/02/2024"));
    assertNull(VigenciaDocumentoService.leerFecha("sin fecha"));
  }
}
