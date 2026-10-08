package com.lexia.api.modules.expedientes.proceso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProcessConfigDiffServiceTest {

  private final ProcessConfigDiffService diffService =
      new ProcessConfigDiffService(new ObjectMapper());

  @Test
  void diffDetectsChangedField() {
    String from = "{\"stages\":[{\"code\":\"e1\",\"label\":\"A\"}]}";
    String to = "{\"stages\":[{\"code\":\"e1\",\"label\":\"B\"}]}";

    var view = diffService.diff(1, 2, from, to);

    assertTrue(view.complete());
    assertEquals(1, view.changes().size());
    assertEquals("CHANGED", view.changes().get(0).changeType());
  }
}
