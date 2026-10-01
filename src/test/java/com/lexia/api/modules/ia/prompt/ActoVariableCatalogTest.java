package com.lexia.api.modules.ia.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaTemplateCatalog;
import com.lexia.api.modules.expedientes.minutas.MinutaVariableBinder;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.prompt.ActoVariableCatalog.ActoVariableSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActoVariableCatalogTest {

  private ActoVariableCatalog catalog;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    PromptRegistryService prompts = new PromptRegistryService();
    prompts.setPrompts(
        java.util.Map.of(
            ActoVariableCatalog.PROMPT_EXTRACCION_ACTO,
            "Extrae variables del acto '${tipoActo}'. Si no consta, null. Solo JSON.",
            ActoVariableCatalog.PROMPT_BIESS_VISION,
            "Sección DATOS APROBADOS PARA DESEMBOLSO. monto_aprobado plazo_aprobado cuota_aprobada tasa_efectiva valor_reposicion porcentaje_valor_financiado"));
    catalog = new ActoVariableCatalog(prompts);
  }

  @Test
  void cadaActoTieneEsquemaMasChicoQueElDefault() {
    Set<String> conocidos = new MinutaViviendaData().toTemplateMap().keySet();
    ActoVariableSchema def = catalog.resolve(null, null);
    assertEquals("DEFAULT", def.codigo());
    assertFalse(def.campos().isEmpty());

    assertEquals(21, catalog.resolve("VIV_TERMINADA_PREF", "CONTRATO_MUTUO").campos().size());
    assertEquals(21, catalog.resolve("TERRENO_Y_VIVIENDA", "CONTRATO_MUTUO").campos().size());
    assertEquals(49, catalog.resolve("TERRENO_Y_VIVIENDA", "MINUTA_COMPRAVENTA").campos().size());
    assertEquals(44, catalog.resolve("VIV_TERMINADA_PREF", "MINUTA_COMPRAVENTA").campos().size());
    assertEquals(19, catalog.resolve("VIV_TERMINADA_IND", "CONTRATO_MUTUO").campos().size());
    assertEquals(17, catalog.resolve("SUSTITUCION_HIPOTECA", "CONTRATO_MUTUO").campos().size());

    for (var entry : catalog.esquemas().entrySet()) {
      assertFalse(entry.getValue().isEmpty(), entry.getKey());
      assertTrue(entry.getValue().size() < def.campos().size(), entry.getKey());
      assertTrue(conocidos.containsAll(entry.getValue()), entry.getKey());
    }
  }

  @Test
  void promptNombraElActoYProhibeRedactar() {
    String prompt =
        catalog.systemPrompt(MinutaTemplateCatalog.PRODUCT_VIV_HIPOTECADA_BIESS, "CONTRATO_MUTUO");
    assertTrue(prompt.contains("VIV_HIPOTECADA_BIESS:CONTRATO_MUTUO"));
    assertTrue(prompt.contains("null"));
    assertTrue(prompt.contains("monto_prestamo"));
    assertTrue(prompt.contains("apoderado_biess"));
    assertFalse(prompt.contains("${tipoActo}"));
  }

  @Test
  void visionPideMetricasDeDesembolso() {
    String prompt = catalog.visionPrompt();
    assertTrue(prompt.contains("DATOS APROBADOS PARA DESEMBOLSO"));
    assertTrue(prompt.contains("monto_aprobado"));
    assertTrue(prompt.contains("cuota_aprobada"));
    assertTrue(prompt.contains("porcentaje_valor_financiado"));
  }

  @Test
  void binderMapeaAliasYNull() throws Exception {
    String json =
        """
        {"acto":"X","variables":{"nombre_vendedor_1":"ANA RUIZ","precio_num":"65000","cedula_vendedor_1":null,"nombre_deudor_1":""}}
        """;
    MinutaViviendaData data = MinutaVariableBinder.bind(mapper.readTree(json), mapper);
    assertEquals("ANA RUIZ", data.getNombreVendedor());
    assertEquals("65000", data.getPrecioCompraventaNumero());
    assertEquals("", data.getCedulaVendedor());
    assertEquals("", data.getNombreConyuge1());
  }

  @Test
  void capturaBiessAceptaCifrasDelVisionPrompt() throws Exception {
    DatosBiessMinuta datos =
        mapper.readValue(
            """
            {"monto_aprobado":85000,"plazo_aprobado":240,"tasa_efectiva":6.5,"cuota_aprobada":410.2,"valor_reposicion":90000,"porcentaje_valor_financiado":80,"apoderado":"ANDRE"}
            """,
            DatosBiessMinuta.class);
    assertEquals("85000", datos.monto());
    assertEquals("240", datos.plazo());
    assertEquals("6.5", datos.tasa());
    assertEquals("410.2", datos.cuota());
    assertEquals("90000", datos.valorReposicion());
    assertEquals("80", datos.porcentajeValorFinanciado());
    assertEquals("ANDRE", datos.apoderado());
  }

  @Test
  void ymlDeclaraLosDosPrompts() throws Exception {
    String yml = Files.readString(Path.of("src/main/resources/application.yml"));
    assertTrue(yml.contains("PROMPT_EXTRACCION_ACTO:"));
    assertTrue(yml.contains("PROMPT_BIESS_VISION:"));
    assertTrue(yml.contains("DATOS APROBADOS PARA DESEMBOLSO"));
    assertTrue(yml.contains("monto_aprobado"));
    assertTrue(yml.contains("No redactes cláusulas"));
  }
}
