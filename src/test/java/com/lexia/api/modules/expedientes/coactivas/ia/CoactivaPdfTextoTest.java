package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto.Coincidencia;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto.TextoOcr;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CoactivaPdfTextoTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void formateaFojasDesdePagesDeAzure() throws Exception {
    TextoOcr texto =
        CoactivaPdfTexto.formatear(
            json.readTree(
                """
                {"content":"PAGARE BanEcuador",
                 "pages":[
                   {"pageNumber":1,"lines":[{"content":"PAGARE BanEcuador"}]},
                   {"pageNumber":2,"lines":[{"content":"Liquidacion previa"}]}
                 ]}
                """));
    assertTrue(texto.texto().contains("--- FOJA 1 ---"));
    assertTrue(texto.texto().contains("--- FOJA 2 ---"));
    assertTrue(texto.texto().contains("PAGARE BanEcuador"));
    assertTrue(texto.texto().contains("Liquidacion previa"));
    assertEquals(2, texto.paginas());
    assertEquals(2, CoactivaPdfTexto.contarFojas(texto.texto()));
    assertEquals("PAGARE BanEcuador".length(), texto.caracteres());
  }

  @Test
  void sinLineasUsaWords() throws Exception {
    TextoOcr texto =
        CoactivaPdfTexto.formatear(
            json.readTree(
                """
                {"content":"Pagare a la orden",
                 "pages":[{"pageNumber":3,"words":[{"content":"Pagare"},{"content":"orden"}]}]}
                """));
    assertTrue(texto.texto().contains("--- FOJA 3 ---"));
    assertTrue(texto.texto().contains("Pagare orden"));
  }

  @Test
  void pdfConTextoVaDirectoAAzure() throws Exception {
    AzureOcrService ocr = mock(AzureOcrService.class);
    when(ocr.isConfigured()).thenReturn(true);
    when(ocr.analizarLectura(any(), eq("application/pdf")))
        .thenReturn(
            json.readTree(
                """
                {"content":"PAGARE a la orden BanEcuador titulo de credito",
                 "pages":[{"pageNumber":1,"lines":[{"content":"PAGARE a la orden BanEcuador titulo de credito"}]}]}
                """));
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F', '-', '1'};
    TextoOcr texto = new CoactivaPdfTexto(ocr, null).extraer(pdf, "application/pdf", "juicio.pdf");
    verify(ocr).analizarLectura(pdf, "application/pdf");
    assertTrue(texto.texto().contains("--- FOJA 1 ---"));
    assertTrue(texto.texto().contains("PAGARE"));
  }

  @Test
  void reparaMojibakeLatin1YConservaUnicode() {
    assertEquals("Prohibición", CoactivaPdfTexto.asegurarUtf8("Prohibici\u00C3\u00B3n"));
    assertEquals("Señor", CoactivaPdfTexto.asegurarUtf8("Se\u00C3\u00B1or"));
    assertEquals("Prohibición", CoactivaPdfTexto.asegurarUtf8("Prohibición"));
    assertEquals("HORA: 12:00 Oficio", CoactivaPdfTexto.asegurarUtf8("HORA: 12:00 Oficio"));
    assertEquals("Prohibici\u00EF\u00BF\u00BD", CoactivaPdfTexto.asegurarUtf8("Prohibici\u00EF\u00BF\u00BD"));
  }

  @Test
  void buscaFojaYExtracto() {
    String texto =
        """
        --- FOJA 44 ---
        otros datos
        --- FOJA 45 ---
        Se notifica al garante solidario de la obligación
        """;
    List<Coincidencia> hits = CoactivaPdfTexto.buscar(texto, "Garante");
    assertEquals(1, hits.size());
    assertEquals(45, hits.get(0).foja());
    assertTrue(hits.get(0).extracto().toLowerCase(Locale.ROOT).contains("garante solidario"));
    assertTrue(hits.get(0).extracto().startsWith("..."));
    assertTrue(CoactivaPdfTexto.buscar(texto, "a").isEmpty());
    assertTrue(CoactivaPdfTexto.buscar("", "garante").isEmpty());
  }

  @Test
  void calidadInsuficienteSiHayFojasYCasiNadaDeTexto() throws Exception {
    TextoOcr pobre =
        CoactivaPdfTexto.formatear(
            json.readTree(
                """
                {"content":"x",
                 "pages":[
                   {"pageNumber":1,"lines":[{"content":"x"}]},
                   {"pageNumber":2,"lines":[]},
                   {"pageNumber":3,"lines":[]},
                   {"pageNumber":4,"lines":[]},
                   {"pageNumber":5,"lines":[]},
                   {"pageNumber":6,"lines":[]}
                 ]}
                """));
    assertTrue(CoactivaPdfTexto.calidadInsuficiente(pobre));
    TextoOcr util =
        new TextoOcr("a".repeat(50), 6, 50);
    assertFalse(CoactivaPdfTexto.calidadInsuficiente(util));
    assertFalse(CoactivaPdfTexto.calidadInsuficiente(new TextoOcr("x", 5, 1)));
  }
}
