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
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaPdfTexto.TextoOcr;
import com.lexia.api.modules.ia.ocr.AzureOcrService;
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
    TextoOcr texto = new CoactivaPdfTexto(ocr).extraer(pdf, "application/pdf", "juicio.pdf");
    verify(ocr).analizarLectura(pdf, "application/pdf");
    assertTrue(texto.texto().contains("--- FOJA 1 ---"));
    assertTrue(texto.texto().contains("PAGARE"));
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
