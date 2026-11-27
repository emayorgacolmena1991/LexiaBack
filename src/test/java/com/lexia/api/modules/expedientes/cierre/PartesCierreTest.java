package com.lexia.api.modules.expedientes.cierre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.comparecientes.Compareciente;
import com.lexia.api.modules.expedientes.comparecientes.RolCompareciente;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartesCierreTest {

  private static Compareciente persona(RolCompareciente rol, int orden, String nombre, String id) {
    Compareciente c = Compareciente.nuevo(UUID.randomUUID(), UUID.randomUUID(), "MANUAL", null);
    c.asignar(rol.parteFija(), rol, orden, nombre, id, null, null, null, null, false, false);
    return c;
  }

  private static MinutaViviendaData minuta() {
    MinutaViviendaData d = new MinutaViviendaData();
    d.setNombreConyuge1("Comprador Minuta");
    d.setCedulaConyuge1("0900000010");
    d.setNombreVendedor("Vendedor Minuta");
    d.setCedulaVendedor("0900000020");
    return d;
  }

  @Test
  void conListaUsaLosComparecientes() {
    var filas =
        List.of(
            persona(RolCompareciente.COMPRADOR, 0, "Ana Sintética", "0900000001"),
            persona(RolCompareciente.VENDEDOR, 0, "Beto Sintético", "0900000002"));
    var partes = PartesCierre.de(filas, minuta());
    assertEquals("ANA SINTÉTICA", PartesCierre.nombres(partes.compradores()));
    assertEquals("BETO SINTÉTICO", PartesCierre.nombres(partes.vendedores()));
  }

  @Test
  void sinListaUsaLaMinuta() {
    var partes = PartesCierre.de(List.of(), minuta());
    assertEquals("COMPRADOR MINUTA", PartesCierre.nombres(partes.compradores()));
    assertEquals("0900000010", partes.compradores().get(0).identificacion());
    assertEquals("VENDEDOR MINUTA", PartesCierre.nombres(partes.vendedores()));
    assertTrue(CartaFinanciamiento.faltantes(partes.compradores(), "NIP-1").isEmpty());
  }

  @Test
  void sinNingunoCartaEInformeListanFaltantes() {
    var partes = PartesCierre.de(List.of(), new MinutaViviendaData());
    assertTrue(partes.compradores().isEmpty());
    assertEquals(
        List.of("Compradores (lista de comparecientes o minuta)", "NIP"),
        CartaFinanciamiento.faltantes(partes.compradores(), ""));
    var datos =
        new InformeLegal.Datos(
            "NIP-1", PartesCierre.nombres(partes.compradores()), "T", "D", "P", "C", "G", "1,00", "N",
            "f", "f", PartesCierre.nombres(partes.vendedores()), "", "Guayaquil", "f", "", "", true, null);
    assertTrue(InformeLegal.faltantes(datos).contains("Propietarios / deudores (compradores)"));
    assertTrue(InformeLegal.faltantes(datos).contains("Vendedores"));
  }
}
