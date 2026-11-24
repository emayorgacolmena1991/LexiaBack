package com.lexia.api.modules.expedientes.coactivas.delegados;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Qué delegado firma hoy las actuaciones de un expediente. El delegado queda guardado en el
 * expediente al crearlo, pero rota (vacaciones de 15 días, cambio de oficinas): el que firma es el
 * vigente en la fecha.
 *
 * <ol>
 *   <li>Un reemplazo temporal vigente en la oficina (tiene {@code vigente_hasta}) gana, salvo que el
 *       delegado del expediente siga vigente y no pertenezca a esa oficina.
 *   <li>Si no, el delegado del expediente si está vigente.
 *   <li>Si no, el único vigente de la oficina.
 *   <li>Si no hay a quién elegir, el del expediente con aviso (la generación no se bloquea).
 * </ol>
 */
public final class DelegadoFirmante {

  /** {@code motivo} no nulo cuando el firmante no es el delegado guardado o no está vigente. */
  public record Eleccion(CoactivaDelegado delegado, String motivo) {}

  private DelegadoFirmante() {}

  public static Eleccion elegir(
      CoactivaDelegado delExpediente, List<CoactivaDelegado> deLaOficina, LocalDate fecha) {
    List<CoactivaDelegado> vigentes = deLaOficina.stream().filter(d -> d.vigenteEn(fecha)).toList();
    boolean expVigente = delExpediente != null && delExpediente.vigenteEn(fecha);
    boolean expEnOficina =
        delExpediente != null && deLaOficina.stream().anyMatch(d -> d.getId().equals(delExpediente.getId()));

    Optional<CoactivaDelegado> reemplazo =
        vigentes.stream()
            .filter(d -> d.getVigenteHasta() != null)
            .filter(d -> delExpediente == null || !d.getId().equals(delExpediente.getId()))
            .max(Comparator.comparing(CoactivaDelegado::getVigenteDesde, Comparator.nullsFirst(Comparator.naturalOrder())));
    if (reemplazo.isPresent() && (!expVigente || expEnOficina)) {
      return new Eleccion(
          reemplazo.get(),
          "Firma " + reemplazo.get().getNombre() + ", reemplazo vigente en la oficina hasta "
              + reemplazo.get().getVigenteHasta() + ".");
    }
    if (expVigente) {
      return new Eleccion(delExpediente, null);
    }
    if (vigentes.size() == 1) {
      CoactivaDelegado unico = vigentes.get(0);
      return new Eleccion(
          unico,
          delExpediente == null
              ? "El expediente no tenía delegado; firma " + unico.getNombre() + ", vigente en la oficina."
              : "El delegado " + delExpediente.getNombre() + " ya no está vigente; firma " + unico.getNombre()
                  + ", vigente en la oficina.");
    }
    return new Eleccion(
        delExpediente,
        delExpediente == null
            ? "El expediente no tiene delegado y la oficina no tiene un único delegado vigente."
            : "El delegado " + delExpediente.getNombre()
                + " no está vigente y la oficina no tiene un único delegado vigente que lo reemplace.");
  }
}
