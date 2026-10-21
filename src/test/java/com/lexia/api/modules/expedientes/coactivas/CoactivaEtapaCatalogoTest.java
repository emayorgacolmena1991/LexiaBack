package com.lexia.api.modules.expedientes.coactivas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoactivaEtapaCatalogoTest {

  @Test
  void reconoceEscritoYHonorarios() {
    assertEquals(CoactivaEtapa.ESCRITO, CoactivaEtapa.parse("ESCRITO").orElseThrow());
    assertEquals(CoactivaEtapa.HONORARIOS, CoactivaEtapa.parse("honorarios").orElseThrow());
    assertEquals(CoactivaEtapa.ESCRITO, CoactivaEtapa.fromTextoLibre("PROVIDENCIA ATENCION ESCRITO").orElseThrow());
    assertEquals(
        CoactivaEtapa.HONORARIOS, CoactivaEtapa.fromTextoLibre("COBRO DE HONORARIOS POR EMBARGO").orElseThrow());
    assertEquals(CoactivaEtapa.EMBARGO, CoactivaEtapa.fromTextoLibre("EMBARGO DE VALORES").orElseThrow());
  }

  @Test
  void stageCodeCabeEnElCatalogoDeProceso() {
    assertTrue(CoactivaEtapa.ESCRITO.stageCode().length() <= 16);
    assertTrue(CoactivaEtapa.HONORARIOS.stageCode().length() <= 16);
    assertEquals("escrito", CoactivaEtapa.ESCRITO.stageCode());
    assertEquals("honorarios", CoactivaEtapa.HONORARIOS.stageCode());
  }
}
