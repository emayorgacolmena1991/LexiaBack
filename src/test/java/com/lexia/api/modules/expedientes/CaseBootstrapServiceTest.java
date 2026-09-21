package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CaseBootstrapServiceTest {

  @Test
  void resolveStageIndexDefaultsToFirstStage() {
    assertEquals(0, CaseBootstrapService.resolveStageIndex(null, List.of()));
    assertEquals(0, CaseBootstrapService.resolveStageIndex("", List.of()));
    assertEquals(0, CaseBootstrapService.resolveStageIndex("Desconocida", List.of()));
  }
}
