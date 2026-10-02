package com.lexia.api.modules.expedientes.coactivas.ingesta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActaIngestaTextoTest {

  @Test
  void sanitizaRuidoOcrDelDeudor() {
    assertEquals("ZAMBRANO SOLORZANO", CoactivaTexto.sanitizarDeudor("@ ZAMBRANO SOLORZANO ."));
    assertEquals("MOLINA GARCIA MARIA", CoactivaTexto.sanitizarDeudor("+ MOLINA GARCIA MARIA"));
    assertEquals("VALENCIA", CoactivaTexto.sanitizarDeudor(". VALENCIA..."));
    assertEquals("NUÑEZ PÉREZ", CoactivaTexto.sanitizarDeudor("  nuñez   pérez "));
    assertNull(CoactivaTexto.sanitizarDeudor("+++"));
    assertNull(CoactivaTexto.sanitizarDeudor("  "));
  }

  @Test
  void separaUecDeCabeceraYOficinaDeFila() {
    List<List<String>> grid =
        List.of(
            List.of("UEC", "OFICINA", "JUICIO", "DEUDOR"),
            List.of("PORTOVIEJO", "BAHIA", "025-2024-00026", "+ MOLINA GARCIA MARIA"),
            List.of("PORTOVIEJO", "CALCETA", "025-2024-00027", "@ ZAMBRANO SOLORZANO ."));
    Resultado resultado = ActaTableParser.interpretar(List.of(grid));
    assertEquals("PORTOVIEJO", resultado.uec());
    assertEquals(2, resultado.filas().size());
    assertEquals("BAHIA", resultado.filas().get(0).oficina());
    assertEquals("CALCETA", resultado.filas().get(1).oficina());
    assertEquals("MOLINA GARCIA MARIA", resultado.filas().get(0).deudor());
    assertEquals("ZAMBRANO SOLORZANO", resultado.filas().get(1).deudor());
  }

  @Test
  void oficinaDeFilaNoUsaLaUecNiElZonal() {
    List<List<String>> grid =
        List.of(
            List.of(
                "#",
                "ZONAL",
                "OFICINA",
                "UEC",
                "OPERACIÓN",
                "NÚMERO DE JUICIO",
                "AÑO",
                "DEUDOR PRINCIPAL",
                "CÉDULA DEUDOR",
                "ETAPA PROCESAL",
                "No. DE FOJAS"),
            List.of(
                "4",
                "PORTOVIEJO",
                "BAHIA",
                "PORTOVIEJO",
                "690192476",
                "069-2019-00168",
                "2019",
                "SOLORZANO ALCIVAR KELVIN TEOVALDO",
                "1111843146",
                "MEDIDAS CAUTELARES",
                "61"),
            List.of(
                "17",
                "PORTOVIEJO",
                "CALCETA",
                "PORTOVIEJO",
                "730335841",
                "073-2022-00015",
                "2022",
                "TOMALA ZAMBRANO FREDDY ROBERTO",
                "0905602215",
                "MEDIDAS CAUTELARES",
                "68"));
    Resultado resultado = ActaTableParser.interpretar(List.of(grid));
    assertEquals("PORTOVIEJO", resultado.uec());
    assertEquals("BAHIA", resultado.filas().get(0).oficina());
    assertEquals("069-2019-00168", resultado.filas().get(0).juicio());
    assertEquals("CALCETA", resultado.filas().get(1).oficina());
  }

  @Test
  void resuelveOficinaPorNombreNormalizadoYNoInventaCodigos() {
    UUID tenant = UUID.randomUUID();
    List<CoactivaOficina> catalogo =
        List.of(
            CoactivaOficina.create(tenant, "MANTA", "Manta", "Manabí", 1),
            CoactivaOficina.create(tenant, "BAHIA_CARAQUEZ", "Bahía de Caráquez", "Manabí", 2),
            CoactivaOficina.create(tenant, "GUAYAQUIL", "Guayaquil", "Guayas", 3));

    assertEquals("MANTA", CoactivaDelegadoService.resolverCodigo("manta", catalogo));
    assertEquals("BAHIA_CARAQUEZ", CoactivaDelegadoService.resolverCodigo("BAHÍA", catalogo));
    assertEquals("GUAYAQUIL", CoactivaDelegadoService.resolverCodigo("GYE", catalogo));
    assertNull(CoactivaDelegadoService.resolverCodigo("Johao Perez", catalogo));
    assertNull(CoactivaDelegadoService.resolverCodigo("  ", catalogo));
    assertEquals("PORTOVIEJO", CoactivaDelegadoService.resolverCodigo("PORTOVIEJO", conAgencias(tenant, catalogo)));
    assertEquals("BAHIA", CoactivaDelegadoService.resolverCodigo("BAHIA", conAgencias(tenant, catalogo)));
    assertEquals("CALCETA", CoactivaDelegadoService.resolverCodigo("CALCETA", conAgencias(tenant, catalogo)));
    assertNull(ActaImportService.oficinaDeFila(null, "GUAYAQUIL"));
    assertNull(ActaImportService.oficinaDeFila("  ", "GUAYAQUIL"));
    assertEquals("BAHIA", ActaImportService.oficinaDeFila("Bahía", "BAHIA"));
    assertEquals("CALCETA", ActaImportService.oficinaDeFila("CALCETA", null));
  }

  @Test
  void leeUecEscritaJuntoALaEtiqueta() {
    assertEquals("PORTOVIEJO", ActaTableParser.uecEnTexto("ACTA DE ENTREGA\nUEC: PORTOVIEJO\nOFICINA BAHIA"));
    assertEquals("PORTOVIEJO", ActaTableParser.uecEnTexto("U.E.C. PORTOVIEJO"));
    assertNull(ActaTableParser.uecEnTexto("OFICINA GUAYAQUIL"));
  }

  private static List<CoactivaOficina> conAgencias(UUID tenant, List<CoactivaOficina> base) {
    return List.of(
        base.get(0),
        base.get(1),
        base.get(2),
        CoactivaOficina.create(tenant, "PORTOVIEJO", "Portoviejo", "Manabí", 4),
        CoactivaOficina.create(tenant, "BAHIA", "Bahía", "Manabí", 5),
        CoactivaOficina.create(tenant, "CALCETA", "Calceta", "Manabí", 6));
  }
}
