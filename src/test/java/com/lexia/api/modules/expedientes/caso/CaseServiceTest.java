package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CaseServiceTest {

  @Test
  void renderCaseCodeReplacesYearAndSequence() {
    assertEquals("LEX-2026-007", CaseService.renderCaseCode("LEX-YYYY-###", 7));
  }
}
