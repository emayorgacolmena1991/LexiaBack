package com.lexia.api.modules.ia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import java.util.List;
import org.junit.jupiter.api.Test;

class IaModelRoutingTest {

  @Test
  void aprobadoSeQuedaEnElModeloLiviano() {
    var limpio = extraccion("APPROVED", List.of());
    assertFalse(IaModelRouting.necesitaPesado(limpio));
    assertNull(IaModelRouting.motivo(limpio));
  }

  @Test
  void observacionAltaPideModeloPesado() {
    var alta = extraccion("WITH_OBSERVATIONS", List.of(new Observacion("X", "HIGH", "gravamen")));
    assertTrue(IaModelRouting.necesitaPesado(alta));
    assertEquals("INCOHERENCIA_ALTA", IaModelRouting.motivo(alta));
    assertEquals(
        "OBSERVACION_MEDIA",
        IaModelRouting.motivo(
            extraccion("WITH_OBSERVATIONS", List.of(new Observacion("Y", "MEDIA", "fecha")))));
  }

  private static ExtraccionExpedienteCompleto extraccion(String estado, List<Observacion> observaciones) {
    return ExtraccionExpedienteCompleto.ok(
        new ProcesarExpedienteCompletoPayload(
            List.of(), new DatosExtraidos(null, null, null), new Dictamen(estado, "ok", observaciones)));
  }
}
