package com.lexia.api.modules.expedientes.coactivas.ingesta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import java.util.List;
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
}
