package com.lexia.api.modules.expedientes.coactivas.delegados;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelegadoFirmanteTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final LocalDate HOY = LocalDate.of(2026, 11, 24);

  @Test
  void delegadoDelExpedienteVigenteFirma() {
    CoactivaDelegado titular = delegado("Titular", LocalDate.of(2025, 1, 1), null);

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(titular, List.of(titular), HOY);

    assertThat(e.delegado()).isSameAs(titular);
    assertThat(e.motivo()).isNull();
  }

  @Test
  void reemplazoPorVacacionesGanaAlTitularDeLaOficina() {
    CoactivaDelegado titular = delegado("Titular", LocalDate.of(2025, 1, 1), null);
    CoactivaDelegado reemplazo = delegado("Reemplazo", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30));

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(titular, List.of(titular, reemplazo), HOY);

    assertThat(e.delegado()).isSameAs(reemplazo);
    assertThat(e.motivo()).contains("Reemplazo").contains("2026-11-30");
  }

  @Test
  void terminadoElReemplazoVuelveElTitular() {
    CoactivaDelegado titular = delegado("Titular", LocalDate.of(2025, 1, 1), null);
    CoactivaDelegado reemplazo = delegado("Reemplazo", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15));

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(titular, List.of(titular, reemplazo), HOY);

    assertThat(e.delegado()).isSameAs(titular);
    assertThat(e.motivo()).isNull();
  }

  @Test
  void delegadoVencidoLoReemplazaElUnicoVigenteDeLaOficina() {
    CoactivaDelegado anterior = delegado("Anterior", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 30));
    CoactivaDelegado nuevo = delegado("Nuevo", LocalDate.of(2026, 7, 1), null);

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(anterior, List.of(anterior, nuevo), HOY);

    assertThat(e.delegado()).isSameAs(nuevo);
    assertThat(e.motivo()).contains("Anterior").contains("ya no está vigente");
  }

  @Test
  void variosPermanentesEnLaOficinaRespetaElDelExpediente() {
    CoactivaDelegado a = delegado("A", LocalDate.of(2025, 1, 1), null);
    CoactivaDelegado b = delegado("B", LocalDate.of(2025, 3, 1), null);
    CoactivaDelegado c = delegado("C", LocalDate.of(2025, 6, 1), null);

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(b, List.of(a, b, c), HOY);

    assertThat(e.delegado()).isSameAs(b);
    assertThat(e.motivo()).isNull();
  }

  @Test
  void delegadoVigenteDeOtraOficinaNoLoPisaUnReemplazoAjeno() {
    CoactivaDelegado deOtraOficina = delegado("Otra", LocalDate.of(2025, 1, 1), null);
    CoactivaDelegado reemplazo = delegado("Reemplazo", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30));

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(deOtraOficina, List.of(reemplazo), HOY);

    assertThat(e.delegado()).isSameAs(deOtraOficina);
  }

  @Test
  void sinNadieVigenteSeQuedaElDelExpedienteConAviso() {
    CoactivaDelegado vencido = delegado("Vencido", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 30));

    DelegadoFirmante.Eleccion e = DelegadoFirmante.elegir(vencido, List.of(vencido), HOY);

    assertThat(e.delegado()).isSameAs(vencido);
    assertThat(e.motivo()).contains("no está vigente");
  }

  @Test
  void expedienteSinDelegadoTomaElUnicoVigente() {
    CoactivaDelegado unico = delegado("Único", LocalDate.of(2025, 1, 1), null);

    assertThat(DelegadoFirmante.elegir(null, List.of(unico), HOY).delegado()).isSameAs(unico);
    assertThat(DelegadoFirmante.elegir(null, List.of(), HOY).delegado()).isNull();
  }

  @Test
  void delegadoInactivoNoFirma() {
    CoactivaDelegado inactivo = delegado("Inactivo", LocalDate.of(2025, 1, 1), null);
    inactivo.update("Inactivo", null, null, null, null, LocalDate.of(2025, 1, 1), null, null, false, null);
    CoactivaDelegado activo = delegado("Activo", LocalDate.of(2025, 1, 1), null);

    assertThat(DelegadoFirmante.elegir(inactivo, List.of(inactivo, activo), HOY).delegado()).isSameAs(activo);
  }

  private static CoactivaDelegado delegado(String nombre, LocalDate desde, LocalDate hasta) {
    CoactivaDelegado d = CoactivaDelegado.create(TENANT, nombre, null);
    d.update(nombre, null, null, null, null, desde, hasta, null, true, null);
    return d;
  }
}
