package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.minutas.ExpedienteVariablesService.VariablesConsolidadas;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog;
import com.lexia.api.modules.ia.prompt.PromptRegistryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpedienteVariablesServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final DocxMinutaRenderer renderer = new DocxMinutaRenderer();
  private final MinutaTemplateCatalog plantillas = new MinutaTemplateCatalog();
  private final ExpedienteVariablesService service =
      new ExpedienteVariablesService(
          new ActoVariableCatalog(new PromptRegistryService()), renderer, mapper);
  private final MinutaTemplateDescriptor mutuo =
      plantillas.require(MinutaTemplateCatalog.PRODUCT_SUSTITUCION_HIPOTECA, "CONTRATO_MUTUO");

  @Test
  void fusionRespetaPrecedenciaLlmBiessOverrides() {
    MinutaViviendaData llm = new MinutaViviendaData();
    llm.setNombreConyuge1("JUAN PEREZ");
    llm.setMontoPrestamo("40000");
    llm.setMontoPrestamoLetras("CUARENTA MIL");
    llm.setPlazoCredito("180");

    DatosBiessMinuta biess = new DatosBiessMinuta("45000", "6.5", "", "410.20", "ANDRE");
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("plazo_credito", "240");
    overrides.put("profesion_conyuge_1", "Ingeniero");
    overrides.put("nombre_conyuge_1", "");

    MinutaViviendaData out = service.fusionar(llm, biess, overrides);

    assertEquals("45000", out.getMontoPrestamo(), "BIESS pisa al LLM");
    assertEquals(
        "CUARENTA Y CINCO MIL", out.getMontoPrestamoLetras(), "letras recalculadas desde la cifra BIESS");
    assertEquals("6.5", out.getTasaInteresInicial());
    assertEquals("240", out.getPlazoCredito(), "override pisa a LLM; BIESS vacío no pisa");
    assertEquals("410.20", out.getCuotaCredito());
    assertEquals("ANDRE", out.getApoderadoBiess());
    assertEquals("Ingeniero", out.getProfesionConyuge1());
    assertEquals("", out.getNombreConyuge1(), "override vacío sí limpia a propósito");
    assertEquals("JUAN PEREZ", llm.getNombreConyuge1(), "no muta la entrada");
  }

  @Test
  void letrasSeCalculanSiempreDesdeLaCifraIgnorandoAlLlm() {
    MinutaViviendaData llm = new MinutaViviendaData();
    llm.setMontoPrestamo("85,000.00");
    llm.setMontoPrestamoLetras("quinientos");

    assertEquals(
        "OCHENTA Y CINCO MIL", service.fusionar(llm, null, Map.of()).getMontoPrestamoLetras());

    DatosBiessMinuta biess = new DatosBiessMinuta("$ 85.000,50", "", "", "", "");
    assertEquals(
        "OCHENTA Y CINCO MIL CON 50/100",
        service.fusionar(llm, biess, Map.of()).getMontoPrestamoLetras());

    assertEquals(
        "UN MILLÓN",
        service.fusionar(llm, biess, Map.of("monto_prestamo", "1000000")).getMontoPrestamoLetras(),
        "monto manual también recalcula");
  }

  @Test
  void letrasIgnoranOverridesManuales() {
    MinutaViviendaData llm = new MinutaViviendaData();
    llm.setMontoPrestamo("85000");

    MinutaViviendaData out =
        service.fusionar(llm, null, Map.of("monto_prestamo_literal", "NOVENTA MIL"));
    assertEquals("OCHENTA Y CINCO MIL", out.getMontoPrestamoLetras());

    out =
        service.fusionar(
            llm, null, Map.of("monto_prestamo_letras", "NOVENTA MIL", "monto_prestamo", "90000"));
    assertEquals("NOVENTA MIL", out.getMontoPrestamoLetras(), "sigue a la cifra corregida");

    Map<String, Object> entrada = new LinkedHashMap<>();
    entrada.put("monto_prestamo_letras", "CUALQUIER COSA");
    assertFalse(service.normalizarOverrides(mutuo, entrada).containsKey("monto_prestamo_letras"));
    assertFalse(
        service.leerOverrides("{\"monto_prestamo_letras\":\"X\"}").containsKey("monto_prestamo_letras"));
  }

  @Test
  void letrasPendientesSiLaCifraFaltaOEsInvalida() {
    MinutaViviendaData llm = new MinutaViviendaData();
    llm.setMontoPrestamoLetras("quinientos");
    assertEquals("", service.fusionar(llm, null, Map.of()).getMontoPrestamoLetras());

    llm.setMontoPrestamo("ochenta mil");
    assertEquals("", service.fusionar(llm, null, Map.of()).getMontoPrestamoLetras());
    VariablesConsolidadas c = service.consolidar(mutuo, service.fusionar(llm, null, Map.of()));
    assertTrue(c.variablesPendientes().contains("monto_prestamo_letras"));
  }

  @Test
  void origenDeLasLetrasEsAuto() {
    MinutaViviendaData extraidos = new MinutaViviendaData();
    extraidos.setMontoPrestamo("85000");

    DatosBiessMinuta sinBiess = DatosBiessMinuta.empty();
    var o = service.origenes(mutuo, extraidos, sinBiess, Map.of());
    assertEquals("AUTO", o.origenes().get("monto_prestamo_letras"));

    o =
        service.origenes(
            mutuo, extraidos, sinBiess, Map.of("monto_prestamo_letras", "OCHENTA Y CINCO MIL"));
    assertEquals("AUTO", o.origenes().get("monto_prestamo_letras"), "nunca es manual");
    assertFalse(o.valoresExtraidos().containsKey("monto_prestamo_letras"));

    o = service.origenes(mutuo, new MinutaViviendaData(), sinBiess, Map.of("monto_prestamo", "85000"));
    assertEquals("AUTO", o.origenes().get("monto_prestamo_letras"), "también con monto corregido");
  }

  @Test
  void consolidadoListaPendientesSoloDeTagsEnPlantilla() {
    MinutaViviendaData data = new MinutaViviendaData();
    data.setNombreConyuge1("JUAN PEREZ");
    data.setMontoPrestamo("45000");

    VariablesConsolidadas c = service.consolidar(mutuo, data);

    assertTrue(c.variables().containsKey("nombre_conyuge_1"));
    assertEquals("JUAN PEREZ", c.variables().get("nombre_conyuge_1"));
    assertEquals("", c.variables().get("tasa_interes_inicial"));
    assertTrue(c.variablesPendientes().contains("tasa_interes_inicial"));
    assertFalse(c.variablesPendientes().contains("nombre_conyuge_1"));
    assertFalse(c.variablesPendientes().contains("monto_prestamo"));
    assertFalse(c.completo());
    assertEquals("Tasa de interés inicial", c.etiquetas().get("tasa_interes_inicial"));
    for (String p : c.variablesPendientes()) {
      assertTrue(c.variables().containsKey(p), p);
    }
  }

  @Test
  void overridesDescartaTagsAjenosYConvierteNumeros() {
    Map<String, Object> entrada = new LinkedHashMap<>();
    entrada.put("tasa_interes_inicial", 6.50);
    entrada.put("monto_prestamo_numero", 85000);
    entrada.put("campo_inventado", "x");
    entrada.put("cuota_credito", 450.50);

    Map<String, String> out = service.normalizarOverrides(mutuo, entrada);

    assertEquals("6.5", out.get("tasa_interes_inicial"));
    assertEquals("85000", out.get("monto_prestamo"), "alias → canónico");
    assertFalse(out.containsKey("campo_inventado"));
    assertFalse(out.containsKey("cuota_credito"), "tag válido pero ajeno a este acto");
  }

  @Test
  void origenesDistinguenIaBiessYManualConValorRestaurable() {
    MinutaViviendaData extraidos = new MinutaViviendaData();
    extraidos.setNombreConyuge1("JUAN PEREZ");
    extraidos.setPlazoCredito("180");
    DatosBiessMinuta biess = new DatosBiessMinuta("45000", "6.5", "", "", "ANDRE");
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("tasa_interes_inicial", "7");
    overrides.put("plazo_credito", "240");

    var o = service.origenes(mutuo, extraidos, biess, overrides);

    assertEquals("IA", o.origenes().get("nombre_conyuge_1"));
    assertEquals("BIESS", o.origenes().get("monto_prestamo"));
    assertEquals("MANUAL", o.origenes().get("tasa_interes_inicial"));
    assertEquals("6.5", o.valoresExtraidos().get("tasa_interes_inicial").valor());
    assertEquals("BIESS", o.valoresExtraidos().get("tasa_interes_inicial").origen());
    assertEquals("180 meses", o.valoresExtraidos().get("plazo_credito").valor());
    assertEquals("IA", o.valoresExtraidos().get("plazo_credito").origen());
    assertFalse(o.origenes().containsKey("cuota_credito"), "sin valor no hay origen");

    service.quitarOverrides(overrides, java.util.List.of("tasa_interes_inicial"));
    assertFalse(overrides.containsKey("tasa_interes_inicial"));
    assertTrue(overrides.containsKey("plazo_credito"));
  }

  @Test
  void overridesSerializanIdaYVuelta() {
    Map<String, String> overrides = Map.of("plazo_credito", "240");
    String json = service.escribirOverrides(overrides);
    assertEquals(overrides, service.leerOverrides(json));
    assertTrue(service.leerOverrides(null).isEmpty());
    assertTrue(service.leerOverrides("{malformado").isEmpty());
  }
}
