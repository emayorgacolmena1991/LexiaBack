package com.lexia.api.modules.expedientes.sla;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlaCalendarServiceTest {

  private static final ZoneId ZONE = ZoneId.of("America/Bogota");

  @Test
  void businessHoursSkipsWeekend() {
    Instant friday =
        LocalDate.of(2026, 9, 18).atTime(17, 0).atZone(ZONE).toInstant();
    Instant due =
        SlaCalendarService.addBusinessHours(friday, 4, ZONE, 8, 18, Set.of());
    Instant dueInstant = due;
    assertTrue(dueInstant.isAfter(friday.plusSeconds(3600 * 24)));
  }
}
