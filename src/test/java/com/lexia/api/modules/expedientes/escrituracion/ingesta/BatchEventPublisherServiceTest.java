package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BatchEventPublisherServiceTest {

  @Test
  void ignoraElAvisoDelMismoNodo() {
    assertTrue(BatchEventPublisherService.esPropio("nodo-1|exp|ANALYSIS_COMPLETED|id", "nodo-1"));
    assertFalse(BatchEventPublisherService.esPropio("nodo-2|exp|ANALYSIS_COMPLETED|id", "nodo-1"));
  }
}
