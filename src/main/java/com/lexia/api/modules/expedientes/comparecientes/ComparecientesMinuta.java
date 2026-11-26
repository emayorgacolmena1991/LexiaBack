package com.lexia.api.modules.expedientes.comparecientes;

import com.lexia.api.modules.expedientes.comparecientes.RolCompareciente.Parte;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData.PersonaMinuta;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lista de comparecientes → variables de la minuta. Los tags de 2 personas por parte se llenan en
 * orden (comprador 1 y 2 = {@code conyuge_1}/{@code conyuge_2}; vendedor 1 = {@code vendedor},
 * vendedor 2 = {@code conyuge_vendedor}); con 2 personas el resultado es el mismo de antes. La lista
 * es la fuente de verdad: si una parte tiene una sola persona, el segundo puesto queda vacío.
 */
public final class ComparecientesMinuta {

  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private ComparecientesMinuta() {}

  public static void aplicar(MinutaViviendaData data, List<Compareciente> filas) {
    if (data == null || filas == null || filas.isEmpty()) {
      return;
    }
    List<Compareciente> compradores = otorgantes(filas, Parte.COMPRADORA);
    List<Compareciente> vendedores = otorgantes(filas, Parte.VENDEDORA);

    if (!compradores.isEmpty()) {
      Compareciente c1 = compradores.get(0);
      Compareciente c2 = compradores.size() > 1 ? compradores.get(1) : null;
      poner(data::setNombreConyuge1, c1.getNombres());
      poner(data::setCedulaConyuge1, c1.getIdentificacion());
      poner(data::setProfesionConyuge1, c1.getProfesion());
      poner(data::setFechaNacimientoConyuge1, fecha(c1));
      poner(data::setDiscapacidadConyuge1, discapacidad(c1));
      poner(data::setEstadoCivil, c1.getEstadoCivil());
      poner(data::setNombreConyuge2, c2 == null ? "" : c2.getNombres());
      poner(data::setCedulaConyuge2, c2 == null ? "" : c2.getIdentificacion());
      poner(data::setProfesionConyuge2, c2 == null ? "" : c2.getProfesion());
      poner(data::setFechaNacimientoConyuge2, c2 == null ? "" : fecha(c2));
      poner(data::setDiscapacidadConyuge2, c2 == null ? "" : discapacidad(c2));
    }
    if (!vendedores.isEmpty()) {
      Compareciente v1 = vendedores.get(0);
      Compareciente v2 = vendedores.size() > 1 ? vendedores.get(1) : null;
      poner(data::setNombreVendedor, v1.getNombres());
      poner(data::setCedulaVendedor, v1.getIdentificacion());
      poner(data::setProfesionVendedor, v1.getProfesion());
      poner(data::setFechaNacimientoVendedor, fecha(v1));
      poner(data::setEstadoCivilVendedor, v1.getEstadoCivil());
      poner(data::setNombreConyugeVendedor, v2 == null ? "" : v2.getNombres());
      poner(data::setCedulaConyugeVendedor, v2 == null ? "" : v2.getIdentificacion());
      poner(data::setProfesionVendedor2, v2 == null ? "" : v2.getProfesion());
    }
    data.setCompradoresTexto(texto(compradores));
    data.setVendedoresTexto(texto(vendedores));
    List<PersonaMinuta> lista = new ArrayList<>();
    for (Compareciente c : filas) {
      lista.add(
          new PersonaMinuta(
              c.getParte(),
              c.getRol(),
              c.getNombres(),
              c.getIdentificacion(),
              c.getEstadoCivil(),
              fecha(c),
              c.getDiscapacidad(),
              c.isPersonaJuridica()));
    }
    data.setComparecientes(lista);
  }

  /** Titulares, cónyuges y herederos de la parte, en orden (sin representantes ni apoderados). */
  static List<Compareciente> otorgantes(List<Compareciente> filas, Parte parte) {
    return filas.stream()
        .filter(c -> c.parteEnum() == parte && c.rolEnum().otorgante())
        .sorted(java.util.Comparator.comparingInt(Compareciente::getOrden))
        .toList();
  }

  /**
   * «A, con cédula de identidad No. 0912345678, de estado civil casado; y B, con RUC No. …». Vacío
   * si no hay personas.
   */
  static String texto(List<Compareciente> personas) {
    List<String> partes = new ArrayList<>();
    for (Compareciente c : personas) {
      StringBuilder sb = new StringBuilder(c.getNombres().trim().toUpperCase(Locale.ROOT));
      if (c.getIdentificacion() != null && !c.getIdentificacion().isBlank()) {
        sb.append(c.isPersonaJuridica() ? ", con RUC No. " : ", con cédula de identidad No. ")
            .append(c.getIdentificacion());
      }
      if (!c.isPersonaJuridica() && c.getEstadoCivil() != null && !c.getEstadoCivil().isBlank()) {
        sb.append(", de estado civil ").append(c.getEstadoCivil().trim().toLowerCase(Locale.ROOT));
      }
      partes.add(sb.toString());
    }
    if (partes.size() <= 1) {
      return partes.isEmpty() ? "" : partes.get(0);
    }
    return String.join("; ", partes.subList(0, partes.size() - 1)) + "; y " + partes.get(partes.size() - 1);
  }

  private static String fecha(Compareciente c) {
    return c.getFechaNacimiento() == null ? "" : c.getFechaNacimiento().format(DMY);
  }

  /** Vacío si el documento no lo dice (nunca se inventa «NO»). */
  private static String discapacidad(Compareciente c) {
    if (c.getDiscapacidad() == null) {
      return "";
    }
    return c.getDiscapacidad() ? "SI" : "NO";
  }

  private static void poner(Consumer<String> setter, String valor) {
    setter.accept(valor == null ? "" : valor);
  }
}
