package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class CoactivaPdfTextoTest {

  @Test
  void pdfConTextoMarcaFojaSinAzure() throws Exception {
    byte[] pdf;
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(50, 700);
        cs.showText("PAGARE a la orden BanEcuador titulo de credito y liquidacion previa");
        cs.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      pdf = out.toByteArray();
    }
    String texto = new CoactivaPdfTexto(mock(AzureOcrService.class)).extraer(pdf, "application/pdf", "juicio.pdf");
    assertTrue(texto.contains("--- FOJA 1 ---"));
    assertTrue(texto.contains("PAGARE"));
  }
}
