package com.lexia.api.modules.expedientes.minutas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CapturaBiessTextoTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void tablasGeneranUnaLineaPorFila() throws Exception {
    String json =
        """
        {"content":"x","tables":[{"cells":[
          {"rowIndex":0,"columnIndex":0,"content":"Monto aprobado"},
          {"rowIndex":0,"columnIndex":1,"content":"85.000,50"},
          {"rowIndex":1,"columnIndex":1,"content":"8,69 %"},
          {"rowIndex":1,"columnIndex":0,"content":"Tasa efectiva"}]}]}
        """;
    assertEquals(
        "Monto aprobado: 85.000,50\nTasa efectiva: 8,69 %",
        CapturaBiessService.textoEtiquetaValor(mapper.readTree(json)));
  }

  @Test
  void sinTablasUneLineasDeLaMismaFila() throws Exception {
    String json =
        """
        {"content":"x","pages":[{"lines":[
          {"content":"25 años","polygon":[5,1.02,6,1.02,6,1.22,5,1.22]},
          {"content":"Plazo:","polygon":[1,1,2,1,2,1.2,1,1.2]},
          {"content":"Cuota","polygon":[1,2,2,2,2,2.2,1,2.2]},
          {"content":"410,20","polygon":[5,2.01,6,2.01,6,2.21,5,2.21]}]}]}
        """;
    assertEquals(
        "Plazo: 25 años\nCuota: 410,20",
        CapturaBiessService.textoEtiquetaValor(mapper.readTree(json)));
  }

  @Test
  void ultimoRecursoEsElContentPlano() throws Exception {
    assertEquals(
        "texto plano",
        CapturaBiessService.textoEtiquetaValor(mapper.readTree("{\"content\":\" texto plano \"}")));
  }
}
