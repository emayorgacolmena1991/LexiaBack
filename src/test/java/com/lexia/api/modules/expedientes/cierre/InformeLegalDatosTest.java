package com.lexia.api.modules.expedientes.cierre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InformeLegalDatosTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"85.000,00|85.000,00", "85,000.00|85.000,00", "$ 85.000,50|85.000,50", "85000|85.000,00"})
  void cuantiaAceptaLosFormatosDeLaMinuta(String entrada, String esperado) {
    assertEquals(esperado, CierreExpedienteService.monto(entrada));
  }

  @Test
  void cuantiaIlegibleQuedaVaciaYEnFaltantes() {
    assertEquals("", CierreExpedienteService.monto("ochenta mil"));
    assertEquals("", CierreExpedienteService.monto("85.00.00"));
  }

  @Test
  void descripcionPierdeLinderosYAreaTotal() {
    var r =
        InformeLegal.sinLinderos(
            "Solar número cinco de la manzana tres de la urbanización Prueba, cuyos linderos son:"
                + " por el norte: calle pública con 10 metros");
    assertEquals("Solar número cinco de la manzana tres de la urbanización Prueba", r.texto());
    assertFalse(r.revisar());

    var area =
        InformeLegal.sinLinderos("Departamento 4B del edificio Sintético, con un área total de 80 m2");
    assertEquals("Departamento 4B del edificio Sintético", area.texto());
  }

  @Test
  void descripcionSinMarcasQuedaIgual() {
    var r = InformeLegal.sinLinderos("Villa 12 de la urbanización Prueba, ubicada al norte de la ciudad");
    assertEquals("Villa 12 de la urbanización Prueba, ubicada al norte de la ciudad", r.texto());
    assertFalse(r.revisar());
  }

  @Test
  void descripcionQueNoSeSeparaConSeguridadVaTalCualConAviso() {
    String texto = "Linderos: norte con lote 2, sur con calle";
    var r = InformeLegal.sinLinderos(texto);
    assertEquals(texto, r.texto());
    assertTrue(r.revisar());
  }
}
