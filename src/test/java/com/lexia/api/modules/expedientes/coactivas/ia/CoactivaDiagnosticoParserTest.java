package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnosticoParser.Parse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoactivaDiagnosticoParserTest {

  private CoactivaDiagnosticoParser parser;

  @BeforeEach
  void setUp() {
    parser = new CoactivaDiagnosticoParser(new ObjectMapper());
  }

  @Test
  void normalizaEtapaYFojas() {
    Parse r =
        parser.parse(
            """
            {"porcentaje_completitud": 85,
             "etapa_procesal_detectada": "RATIFICACION DE MEDIDAS CAUTELARES",
             "documentos_identificados": [
               {"tipo": "PAGARE", "foja_inicio": 1, "foja_fin": 4, "presente": true},
               {"tipo": "LIQUIDACION_ACTUALIZADA", "presente": false}
             ],
             "alertas_inconsistencias": ["Liquidación vencida."],
             "datos_extraidos": {"juicio": "069-2019-00168", "monto_mora": 12500.00},
             "siguiente_accion_sugerida": "Pedir liquidación en COVIS."}
            """);
    assertTrue(r.ok());
    assertEquals(85, r.diagnostico().porcentajeCompletitud());
    assertEquals("MEDIDAS_CAUTELARES", r.diagnostico().etapaNormalizada());
    assertEquals(2, r.diagnostico().documentos().size());
    assertEquals(1, r.diagnostico().documentos().get(0).fojaInicio());
    assertNull(r.diagnostico().documentos().get(1).fojaInicio());
    assertFalse(r.diagnostico().documentos().get(1).presente());
    assertEquals("Pedir liquidación en COVIS.", r.diagnostico().siguienteAccion());
    assertTrue(r.diagnostico().json().contains("MEDIDAS_CAUTELARES"));
  }

  @Test
  void porcentajeDesdeDocumentosSiFalta() {
    Parse r =
        parser.parse(
            """
            {"documentos_identificados": [
              {"tipo": "OPI", "presente": true},
              {"tipo": "RAZON_NOTIFICACION", "presente": false}
            ]}
            """);
    assertTrue(r.ok());
    assertEquals(50, r.diagnostico().porcentajeCompletitud());
  }

  @Test
  void vacioEsError() {
    assertFalse(parser.parse("").ok());
    assertFalse(parser.parse("no es json").ok());
  }
}
