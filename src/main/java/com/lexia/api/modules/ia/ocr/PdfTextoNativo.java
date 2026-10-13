package com.lexia.api.modules.ia.ocr;

import java.io.IOException;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Si el 80 % de las páginas ya trae texto, no hace falta Azure. */
@Component
public class PdfTextoNativo {

  public Optional<String> extraerSiDenso(byte[] pdf) {
    if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P') {
      return Optional.empty();
    }
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      int paginas = doc.getNumberOfPages();
      if (paginas == 0) {
        return Optional.empty();
      }
      PDFTextStripper stripper = new PDFTextStripper();
      int conTexto = 0;
      for (int i = 1; i <= paginas; i++) {
        stripper.setStartPage(i);
        stripper.setEndPage(i);
        String pagina = stripper.getText(doc).replaceAll("\\s", "");
        if (pagina.length() > 40) {
          conTexto++;
        }
      }
      if (conTexto * 100 / paginas < 80) {
        return Optional.empty();
      }
      stripper.setStartPage(1);
      stripper.setEndPage(paginas);
      String texto = stripper.getText(doc).trim();
      return texto.isBlank() ? Optional.empty() : Optional.of(texto);
    } catch (IOException ex) {
      return Optional.empty();
    }
  }
}
