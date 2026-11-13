package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lexia.api.common.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NotaryRemisionTest {

  @Test
  void createMarcaPersistableNewSinVersionPrevia() {
    NotaryRemision remision = sample();
    assertThat(remision.isNew()).isTrue();
    assertThat(remision.getId()).isNotNull();
  }

  @Test
  void avanzaPasosHastaCompletadoTrasRecibir() {
    NotaryRemision remision = recibida();
    remision.advanceStep();
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.EN_TRAMITE.name());
    for (int i = 0; i < NotaryProcessSteps.COUNT - 2; i++) {
      remision.advanceStep();
    }
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.NOTARIA_COMPLETADA.name());
    assertThat(remision.getPasoNotarial()).isEqualTo((short) (NotaryProcessSteps.COUNT - 1));
  }

  @Test
  void avanzarSinRecibirResponde409() {
    NotaryRemision remision = sample();

    ApiException error = assertThrows(ApiException.class, remision::advanceStep);

    assertEquals(HttpStatus.CONFLICT, error.getStatus());
    assertEquals(NotaryRemision.DEBE_ESTAR_RECIBIDA, error.getMessage());
    assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.ENVIADO_A_NOTARIA.name());
    assertThat(remision.getPasoNotarial()).isZero();
  }

  @Test
  void avanzarCompletadaODevueltaResponde409() {
    NotaryRemision completada = completada();
    NotaryRemision devuelta = recibida();
    devuelta.devolverAlBufete("Falta el avalúo");

    for (NotaryRemision remision : new NotaryRemision[] {completada, devuelta}) {
      assertEquals(
          HttpStatus.CONFLICT, assertThrows(ApiException.class, remision::advanceStep).getStatus());
    }
  }

  @Test
  void devolverDesdeEnviadaODevueltaResponde409() {
    NotaryRemision enviada = sample();
    NotaryRemision devuelta = recibida();
    devuelta.devolverAlBufete("Falta el avalúo");

    for (NotaryRemision remision : new NotaryRemision[] {enviada, devuelta}) {
      String antes = remision.getEstado();
      ApiException error =
          assertThrows(ApiException.class, () -> remision.devolverAlBufete("Falta el avalúo"));
      assertEquals(HttpStatus.CONFLICT, error.getStatus());
      assertEquals(antes, remision.getEstado());
    }
  }

  @Test
  void devolverAntesDeCompletarSinObservacionResponde400() {
    NotaryRemision recibida = recibida();
    NotaryRemision enTramite = recibida();
    enTramite.advanceStep();

    for (NotaryRemision remision : new NotaryRemision[] {recibida, enTramite}) {
      for (String vacia : new String[] {null, "", "   "}) {
        ApiException error =
            assertThrows(ApiException.class, () -> remision.devolverAlBufete(vacia));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(NotaryRemision.FALTA_OBSERVACION, error.getMessage());
      }
      assertThat(remision.getEstado()).isNotEqualTo(NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name());
    }
  }

  @Test
  void devolverRecibidaOEnTramiteConObservacionQuedaDevuelta() {
    NotaryRemision recibida = recibida();
    NotaryRemision enTramite = recibida();
    enTramite.advanceStep();

    for (NotaryRemision remision : new NotaryRemision[] {recibida, enTramite}) {
      remision.devolverAlBufete("  Falta el avalúo  ");
      assertThat(remision.getEstado()).isEqualTo(NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name());
      assertThat(remision.getObservacionesNotaria()).isEqualTo("Falta el avalúo");
    }
  }

  @Test
  void devolverCompletadaNoExigeObservacion() {
    NotaryRemision sinObservacion = completada();
    NotaryRemision conObservacion = completada();

    sinObservacion.devolverAlBufete(null);
    conObservacion.devolverAlBufete("  Firmado y registrado  ");

    assertThat(sinObservacion.getEstado()).isEqualTo(NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name());
    assertThat(conObservacion.getObservacionesNotaria()).isEqualTo("Firmado y registrado");
  }

  private static NotaryRemision recibida() {
    NotaryRemision remision = sample();
    remision.acknowledgeReceipt();
    return remision;
  }

  private static NotaryRemision completada() {
    NotaryRemision remision = recibida();
    for (int i = 0; i < NotaryProcessSteps.COUNT - 1; i++) {
      remision.advanceStep();
    }
    return remision;
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
