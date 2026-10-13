package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfTextoNativoTest {

  private final PdfTextoNativo extractor = new PdfTextoNativo();

  @Test
  void pdfConTextoNoVaAAzure() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("Comprador 0912345678 comparece en Guayaquil por el inmueble.");
        stream.endText();
      }
      byte[] pdf = aBytes(doc);
      assertTrue(extractor.extraerSiDenso(pdf).orElse("").contains("0912345678"));
    }
  }

  @Test
  void pdfVacioNoBypasea() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      assertTrue(extractor.extraerSiDenso(aBytes(doc)).isEmpty());
    }
  }

  private static byte[] aBytes(PDDocument doc) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    doc.save(out);
    return out.toByteArray();
  }
}
