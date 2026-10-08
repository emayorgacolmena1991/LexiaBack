package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoactivaVariableBinderTest {

  private final CoactivaVariableBinder binder = new CoactivaVariableBinder();
  private static final LocalDate HOY = LocalDate.of(2026, 10, 7);

  @Test
  void precedenciaIaSistemaOverrideYNodata() {
    List<String> tags = List.of("numero_juicio_coactivo", "numero_resolucion_delegacion", "ciudad_actuacion");
    Map<String, String> ia = Map.of("numero_juicio_coactivo", "123", "numero_resolucion_delegacion", "OCR-1");
    Map<String, String> sistema = Map.of("numero_resolucion_delegacion", "RES-9");
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("numero_juicio_coactivo", "999");

    CoactivaVariableBinder.Resultado r = binder.resolver(tags, ia, sistema, overrides, HOY);

    assertEquals("999", r.variables().get("numero_juicio_coactivo"));
    assertEquals("MANUAL", r.origenes().get("numero_juicio_coactivo"));
    assertEquals("RES-9", r.variables().get("numero_resolucion_delegacion"));
    assertEquals("SISTEMA", r.origenes().get("numero_resolucion_delegacion"));
    assertEquals("nodata", r.variables().get("ciudad_actuacion"));
    assertTrue(r.pendientes().contains("ciudad_actuacion"));
    assertEquals("123", r.valoresExtraidos().get("numero_juicio_coactivo"));
  }

  @Test
  void liquidacionCaducaALosTresDias() {
    List<String> tags = List.of("fecha_liquidacion", "monto_liquidacion", "numero_juicio_coactivo");
    Map<String, String> ia =
        Map.of(
            "fecha_liquidacion", "1 de octubre de 2026",
            "monto_liquidacion", "10.00",
            "numero_juicio_coactivo", "77");
    CoactivaVariableBinder.Resultado r = binder.resolver(tags, ia, Map.of(), Map.of(), HOY);

    assertFalse(r.liquidacionVigente());
    assertEquals("nodata", r.variables().get("fecha_liquidacion"));
    assertEquals("nodata", r.variables().get("monto_liquidacion"));
    assertEquals("77", r.variables().get("numero_juicio_coactivo"));
    assertEquals("IA", r.origenes().get("numero_juicio_coactivo"));
  }

  @Test
  void liquidacionDeHaceTresDiasSigueVigente() {
    Map<String, String> sistema = Map.of("fecha_liquidacion", "04/10/2026", "monto_liquidacion", "15.00");
    CoactivaVariableBinder.Resultado r =
        binder.resolver(List.of("fecha_liquidacion", "monto_liquidacion"), Map.of(), sistema, Map.of(), HOY);

    assertTrue(r.liquidacionVigente());
    assertEquals("15.00", r.variables().get("monto_liquidacion"));
    assertEquals("SISTEMA", r.origenes().get("monto_liquidacion"));
  }
}
