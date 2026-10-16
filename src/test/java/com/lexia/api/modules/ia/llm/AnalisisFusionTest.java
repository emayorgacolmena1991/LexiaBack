package com.lexia.api.modules.ia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DocumentoExtraido;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalisisFusionTest {

  @Test
  void elProfundoCompletaSinBorrarLoDeHaiku() {
    var haiku =
        new ProcesarExpedienteCompletoPayload(
            List.of(new DocumentoExtraido("d1", "CEDULA", "ok", Map.of())),
            new DatosExtraidos(new Persona("Ana", "091", null), null, null),
            new Dictamen("WITH_OBSERVATIONS", "haiku", List.of(new Observacion("G", "HIGH", "gravamen"))));
    var sonnet =
        new ProcesarExpedienteCompletoPayload(
            List.of(new DocumentoExtraido("d2", "AVALUO", "otro", Map.of())),
            new DatosExtraidos(new Persona("Otra", "000", "casada"), null, null),
            new Dictamen("REJECTED", "sonnet", List.of(new Observacion("X", "MEDIUM", "otra"))));

    var fusion = AnalisisFusion.fusionar(haiku, sonnet);

    assertEquals("Ana", fusion.datosExtraidos().comprador().nombres());
    assertEquals("casada", fusion.datosExtraidos().comprador().estadoCivil());
    assertEquals(2, fusion.documentosExtraidos().size());
    assertEquals("REJECTED", fusion.dictamen().estado());
    assertEquals(2, fusion.dictamen().observaciones().size());
  }
}
