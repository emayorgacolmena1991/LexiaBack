package com.lexia.api.modules.expedientes.cierre;

import com.lexia.api.modules.expedientes.comparecientes.Compareciente;
import com.lexia.api.modules.expedientes.comparecientes.ComparecientesMinuta;
import com.lexia.api.modules.expedientes.comparecientes.RolCompareciente.Parte;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compradores y vendedores de la carta y del informe: los otorgantes de la lista de comparecientes;
 * si una parte no tiene lista (expedientes anteriores), los nombres y cédulas de la minuta.
 */
public final class PartesCierre {

  private static final Locale ES = Locale.forLanguageTag("es");

  public record Persona(String nombre, String identificacion, boolean personaJuridica) {}

  public record Partes(List<Persona> compradores, List<Persona> vendedores) {}

  private PartesCierre() {}

  public static Partes de(List<Compareciente> filas, MinutaViviendaData minuta) {
    List<Compareciente> lista = filas == null ? List.of() : filas;
    MinutaViviendaData d = minuta == null ? new MinutaViviendaData() : minuta;
    List<Persona> compradores = personas(ComparecientesMinuta.otorgantes(lista, Parte.COMPRADORA));
    if (compradores.isEmpty()) {
      compradores =
          deMinuta(d.getNombreConyuge1(), d.getCedulaConyuge1(), d.getNombreConyuge2(), d.getCedulaConyuge2());
    }
    List<Persona> vendedores = personas(ComparecientesMinuta.otorgantes(lista, Parte.VENDEDORA));
    if (vendedores.isEmpty()) {
      vendedores =
          deMinuta(
              d.getNombreVendedor(),
              d.getCedulaVendedor(),
              d.getNombreConyugeVendedor(),
              d.getCedulaConyugeVendedor());
    }
    return new Partes(compradores, vendedores);
  }

  public static String nombres(List<Persona> personas) {
    return CartaFinanciamiento.unir(personas.stream().map(Persona::nombre).toList());
  }

  private static List<Persona> personas(List<Compareciente> filas) {
    List<Persona> out = new ArrayList<>();
    for (Compareciente c : filas) {
      String nombre = limpio(c.getNombres());
      if (!nombre.isEmpty()) {
        out.add(new Persona(nombre.toUpperCase(ES), limpio(c.getIdentificacion()), c.isPersonaJuridica()));
      }
    }
    return out;
  }

  /** Pares (nombre, cédula) de la minuta; los vacíos se omiten. */
  private static List<Persona> deMinuta(String... pares) {
    List<Persona> out = new ArrayList<>();
    for (int i = 0; i + 1 < pares.length; i += 2) {
      String nombre = limpio(pares[i]);
      if (!nombre.isEmpty()) {
        out.add(new Persona(nombre.toUpperCase(ES), limpio(pares[i + 1]), false));
      }
    }
    return out;
  }

  private static String limpio(String v) {
    return MinutaViviendaData.isMissing(v) ? "" : v.trim();
  }
}
