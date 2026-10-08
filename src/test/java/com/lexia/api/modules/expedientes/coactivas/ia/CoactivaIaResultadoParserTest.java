package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoactivaIaResultadoParserTest {

  private CoactivaIaResultadoParser parser;

  @BeforeEach
  void setUp() {
    parser = new CoactivaIaResultadoParser(new ObjectMapper());
  }

  @Test
  void aprobadoConChecklistYConfianza() {
    CoactivaIaResultado r =
        parser.parse(
            """
            {"estado":"APROBADO","confianza":95,"razon_rechazo":"","checklist_cumplido":["monto_coincide","fechas_validas"]}
            """);
    assertEquals(CoactivaArchivo.IA_APROBADO, r.estado());
    assertEquals(95, r.confianza());
    assertNull(r.razonRechazo());
    assertEquals(2, r.checklistCumplido().size());
    assertTrue(r.checklistCumplido().contains("monto_coincide"));
  }

  @Test
  void rechazadoConMotivo() {
    CoactivaIaResultado r =
        parser.parse(
            """
            {"estado":"RECHAZADO","confianza":70,"razon_rechazo":"Falta la firma del deudor solidario","checklist_cumplido":[]}
            """);
    assertEquals(CoactivaArchivo.IA_RECHAZADO, r.estado());
    assertEquals(70, r.confianza());
    assertEquals("Falta la firma del deudor solidario", r.razonRechazo());
  }

  @Test
  void fenceMarkdown() {
    CoactivaIaResultado r =
        parser.parse(
            """
            ```json
            {"estado":"APROBADO","confianza":80,"razon_rechazo":"","checklist_cumplido":[]}
            ```
            """);
    assertEquals(CoactivaArchivo.IA_APROBADO, r.estado());
    assertEquals(80, r.confianza());
  }

  @Test
  void aliasInglesYConfianza01() {
    CoactivaIaResultado r =
        parser.parse("{\"status\":\"REJECTED\",\"confidence\":0.91,\"reason\":\"sin BanEcuador\"}");
    assertEquals(CoactivaArchivo.IA_RECHAZADO, r.estado());
    assertEquals(91, r.confianza());
    assertEquals("sin BanEcuador", r.razonRechazo());
  }

  @Test
  void validoBoolean() {
    assertEquals(CoactivaArchivo.IA_APROBADO, parser.parse("{\"valido\":true}").estado());
    CoactivaIaResultado rechazado = parser.parse("{\"valido\":false,\"motivo\":\"ilegible\"}");
    assertEquals(CoactivaArchivo.IA_RECHAZADO, rechazado.estado());
    assertEquals("ilegible", rechazado.razonRechazo());
  }

  @Test
  void vacioEsError() {
    CoactivaIaResultado r = parser.parse("   ");
    assertEquals(CoactivaArchivo.IA_ERROR, r.estado());
    assertTrue(r.razonRechazo().contains("vacía"));
  }

  @Test
  void jsonInvalidoEsError() {
    CoactivaIaResultado r = parser.parse("no-es-json");
    assertEquals(CoactivaArchivo.IA_ERROR, r.estado());
    assertTrue(r.razonRechazo().contains("no es JSON"));
  }

  @Test
  void estadoDesconocidoEsError() {
    CoactivaIaResultado r = parser.parse("{\"estado\":\"TALVEZ\"}");
    assertEquals(CoactivaArchivo.IA_ERROR, r.estado());
    assertTrue(r.razonRechazo().contains("desconocido"));
  }

  @Test
  void rechazadoSinMotivoUsaDefault() {
    CoactivaIaResultado r = parser.parse("{\"estado\":\"RECHAZADO\"}");
    assertEquals(CoactivaArchivo.IA_RECHAZADO, r.estado());
    assertTrue(r.razonRechazo().contains("sin indicar"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "VALIDO", "OK", "ACEPTADO"})
  void aliasAprobado(String estado) {
    assertEquals(
        CoactivaArchivo.IA_APROBADO, parser.parse("{\"estado\":\"" + estado + "\"}").estado());
  }

  @Test
  void checklistJsonSerializa() {
    assertEquals("[\"a\",\"b\"]", parser.checklistJson(java.util.List.of("a", "b")));
    assertEquals("[]", parser.checklistJson(null));
  }
}
