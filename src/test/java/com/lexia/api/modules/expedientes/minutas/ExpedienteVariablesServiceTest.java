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
    assertEquals("", out.getMontoPrestamoLetras(), "monto en letras queda pendiente al cambiar la cifra");
    assertEquals("6.5", out.getTasaInteresInicial());
    assertEquals("240", out.getPlazoCredito(), "override pisa a LLM; BIESS vacío no pisa");
    assertEquals("410.20", out.getCuotaCredito());
    assertEquals("ANDRE", out.getApoderadoBiess());
    assertEquals("Ingeniero", out.getProfesionConyuge1());
    assertEquals("", out.getNombreConyuge1(), "override vacío sí limpia a propósito");
    assertEquals("JUAN PEREZ", llm.getNombreConyuge1(), "no muta la entrada");
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
  void overridesSerializanIdaYVuelta() {
    Map<String, String> overrides = Map.of("plazo_credito", "240");
    String json = service.escribirOverrides(overrides);
    assertEquals(overrides, service.leerOverrides(json));
    assertTrue(service.leerOverrides(null).isEmpty());
    assertTrue(service.leerOverrides("{malformado").isEmpty());
  }
}
