package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EstatusProcesoServiceTest {

  @Test
  void laListaInicialTieneSugeridoPorEtapa() {
    assertEquals(10, EstatusProcesoService.ETAPAS.size());
    assertEquals("Minuta en elaboración", EstatusProcesoService.etapa("MINUTA").etiqueta());
    assertTrue(EstatusProcesoService.etapa("CIERRE").sugerido().length() > 0);
    assertNull(EstatusProcesoService.etapa("OTRA"));
  }
}
