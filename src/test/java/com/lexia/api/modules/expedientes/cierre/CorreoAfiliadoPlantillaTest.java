package com.lexia.api.modules.expedientes.cierre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.cierre.PartesCierre.Persona;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorreoAfiliadoPlantillaTest {

  private static final CorreoAfiliado.Firma FIRMA =
      new CorreoAfiliado.Firma("Estudio Prueba", "Abogada Sintética", "0990000000", "firma@ejemplo.test");

  @Test
  void plantillaPorDefectoReemplazaTodosLosMarcadores() {
    String texto =
        CorreoAfiliado.texto(
            "VIVIENDA TERMINADA",
            List.of(new CorreoAfiliado.Grupo("Cédula del comprador", List.of("Está vencida"))),
            FIRMA,
            "");
    assertTrue(texto.contains("Estudio Jurídico Estudio Prueba."));
    assertTrue(texto.contains("«VIVIENDA TERMINADA»"));
    assertTrue(texto.contains("Cédula del comprador:\n  • Está vencida"));
    assertTrue(texto.contains("Ab. Abogada Sintética\nTeléfono: 0990000000\nCorreo: firma@ejemplo.test"));
    assertFalse(texto.contains("{"));
  }

  @Test
  void sinEstudioNoDejaEspacioSuelto() {
    String texto = CorreoAfiliado.texto("P", List.of(), new CorreoAfiliado.Firma("", "", "", ""), null);
    assertTrue(texto.contains("Estudio Jurídico."));
    assertTrue(texto.contains("No se registran observaciones abiertas"));
  }

  @Test
  void plantillaDelTenantReemplazaAlTextoPorDefecto() {
    String texto =
        CorreoAfiliado.texto(
            "PRODUCTO X",
            List.of(new CorreoAfiliado.Grupo("Documentos faltantes", List.of("Avalúo"))),
            FIRMA,
            "Hola. Trámite {producto}.\n{observaciones}\n{firma}");
    assertEquals(
        "Hola. Trámite PRODUCTO X.\nDocumentos faltantes:\n  • Avalúo\n"
            + "Ab. Abogada Sintética\nTeléfono: 0990000000\nCorreo: firma@ejemplo.test\n",
        texto);
  }

  @Test
  void laCartaUsaLaFechaElegida() {
    var datos =
        CartaFinanciamiento.datos(
            List.of(), List.of(new Persona("ANA", "0900000001", false)), "NIP-1", LocalDate.of(2026, 12, 3), null);
    assertEquals("3 de diciembre de 2026", datos.get("fecha_carta"));
    assertEquals("", datos.get("plusvalia_vendedor"));
  }
}
