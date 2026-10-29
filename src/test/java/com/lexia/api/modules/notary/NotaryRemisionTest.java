package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotaryRemisionTest {

  @Test
  void avanzaPasosHastaCompletado() {
    NotaryRemision remision = sample();
    remision.advanceStep();
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.EN_TRAMITE.name());
    for (int i = 0; i < NotaryProcessSteps.COUNT - 2; i++) {
      remision.advanceStep();
    }
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.NOTARIA_COMPLETADA.name());
    assertThat(remision.getPasoNotarial()).isEqualTo((short) (NotaryProcessSteps.COUNT - 1));
  }

  @Test
  void devolverMarcaEstadoYObservaciones() {
    NotaryRemision remision = sample();
    remision.devolverAlBufete("  Firmado y registrado  ");
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name());
    assertThat(remision.getObservacionesNotaria()).isEqualTo("Firmado y registrado");
  }

  private static NotaryRemision sample() {
    return NotaryRemision.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        "EXP-001",
        "Compraventa demo",
        "Acta demo",
        3,
        UUID.randomUUID(),
        "Obs bufete");
  }
}
