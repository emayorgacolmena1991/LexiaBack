package com.lexia.api.modules.expedientes.ejd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lexia.api.modules.expedientes.ejd.EjdValidationEvaluationService.MotivoPresencia;
import com.lexia.api.modules.expedientes.ejd.EjdValidationEvaluationService.PresenciaProducto;
import org.junit.jupiter.api.Test;

class GateG001PresenciaTest {

  @Test
  void elGateDecidePorElCodigoNoPorElMensaje() {
    var conTextoEngañoso =
        new PresenciaProducto(
            "OBSERVATION", "No hay documentos requeridos (texto cambiado)", MotivoPresencia.FALTAN_NO_ESENCIALES);
    assertEquals("PASS", EjdValidationEvaluationService.snapshotDe(conTextoEngañoso).gateResult());

    var sinRequisitos =
        new PresenciaProducto("OBSERVATION", "Mensaje reescrito por alguien", MotivoPresencia.SIN_REQUISITOS);
    assertEquals("REVIEW_REQUIRED", EjdValidationEvaluationService.snapshotDe(sinRequisitos).gateResult());
  }

  @Test
  void soloLosEsencialesBloquean() {
    var esenciales = new PresenciaProducto("FAIL", "Faltan: cédula", MotivoPresencia.FALTAN_ESENCIALES);
    assertEquals("FAIL", EjdValidationEvaluationService.snapshotDe(esenciales).gateResult());

    var completo = new PresenciaProducto("PASS", "Están todos", MotivoPresencia.COMPLETO);
    var snapshot = EjdValidationEvaluationService.snapshotDe(completo);
    assertEquals("PASS", snapshot.gateResult());
    assertNull(snapshot.detail());
  }
}
