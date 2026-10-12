package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico.Documento;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoactivaDiagnosticoMergeTest {

  @Test
  void conservaHitosDeOtroArchivoYAgregaLaPiezaNueva() {
    UUID primero = UUID.randomUUID();
    UUID segundo = UUID.randomUUID();
    CoactivaDiagnosticoMerge.Fusion base =
        CoactivaDiagnosticoMerge.fusionar(
            null,
            null,
            null,
            primero,
            "proceso.pdf",
            "PAGARE",
            new CoactivaDiagnostico(
                50,
                "PREVIA",
                "PREVIA",
                List.of(new Documento("PAGARE", 1, 3, true), new Documento("OPI", null, null, false)),
                List.of("Falta OPI"),
                Map.of("juicio", "025-2024-00026"),
                "Emitir OPI",
                "{}"));

    CoactivaDiagnosticoMerge.Fusion fusion =
        CoactivaDiagnosticoMerge.fusionar(
            base.json(),
            primero,
            Instant.parse("2026-10-01T12:00:00Z"),
            segundo,
            "opi.pdf",
            "OPI",
            new CoactivaDiagnostico(
                80,
                "OPI_EMITIDA",
                "OPI_EMITIDA",
                List.of(new Documento("OPI", 12, 14, true)),
                List.of("OPI sin notificar"),
                Map.of("operacion", "9988"),
                "Notificar OPI",
                "{}"));

    CoactivaDiagnosticoMerge.Vista vista = CoactivaDiagnosticoMerge.vista(fusion.json());
    assertEquals(2, vista.documentosProcesados().size());
    assertEquals("opi.pdf", vista.documentosProcesados().get(1).get("nombre_archivo"));
    assertTrue(vista.hitosAcumulados().stream().anyMatch(h -> "PAGARE".equals(h.get("hito"))));
    assertTrue(vista.hitosAcumulados().stream().anyMatch(h -> "OPI".equals(h.get("hito"))));
    assertTrue(fusion.json().contains("025-2024-00026"));
    assertTrue(fusion.json().contains("9988"));
    assertEquals("OPI_EMITIDA", fusion.etapa());
  }

  @Test
  void conservaEvidenciaDeLaTentativa() {
    String json =
        """
        {"etapa_sugerida":{"codigo":"MEDIDAS_CAUTELARES","evidencia":{"extracto_texto":"Se decreta la retención"}},
         "suficiencia_documental":{"estado_diagnostico":"AMARILLO"},
         "acciones_sugeridas":[{"titulo":"Solicitar liquidación"}]}
        """;
    CoactivaDiagnosticoMerge.Fusion fusion =
        CoactivaDiagnosticoMerge.fusionar(
            null,
            null,
            null,
            UUID.randomUUID(),
            "proceso.pdf",
            "PROVIDENCIA",
            new CoactivaDiagnostico(
                88,
                "MEDIDAS_CAUTELARES",
                "MEDIDAS_CAUTELARES",
                List.of(new Documento("PROVIDENCIA", 3, 3, true)),
                List.of(),
                Map.of(),
                "Solicitar liquidación",
                json));
    assertTrue(fusion.json().contains("Se decreta la retención"));
    assertTrue(fusion.json().contains("AMARILLO"));
    assertTrue(fusion.json().contains("Solicitar liquidación"));
  }
}
