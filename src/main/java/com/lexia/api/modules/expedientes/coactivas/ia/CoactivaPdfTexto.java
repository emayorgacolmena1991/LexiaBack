package com.lexia.api.modules.expedientes.coactivas.ia;

import com.lexia.api.modules.ia.ocr.AzureOcrService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Texto del expediente único. PDF con capa de texto: PDFBox, una marca por foja. Escaneo: Azure
 * OCR en lotes de fojas (el PDF de 200+ páginas no entra en una sola llamada útil).
 */
@Component
public class CoactivaPdfTexto {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaPdfTexto.class);
  static final int LOTE_FOJAS = 20;
  static final int MIN_TEXTO = 80;

  private final AzureOcrService ocr;

  public CoactivaPdfTexto(AzureOcrService ocr) {
    this.ocr = ocr;
  }

  public String extraer(byte[] bytes, String mime, String nombre) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    if (!esPdf(mime, nombre, bytes)) {
      return ocrSeguro(bytes, mime, "archivo");
    }
    String embebido = textoEmbebido(bytes);
    if (embebido.length() >= MIN_TEXTO) {
      LOG.info("OCR expediente origen=pdf-texto chars={}", embebido.length());
      return embebido;
    }
    String azure = ocrPorFojas(bytes);
    LOG.info("OCR expediente origen=azure-fojas chars={}", azure.length());
    return azure.length() >= embebido.length() ? azure : embebido;
  }

  private String textoEmbebido(byte[] bytes) {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return unirFojas(doc);
    } catch (IOException e) {
      LOG.warn("PDFBox falló: {}", e.getMessage());
      return "";
    }
  }

  private String ocrPorFojas(byte[] bytes) {
    if (!ocr.isConfigured()) {
      return "";
    }
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      int paginas = doc.getNumberOfPages();
      StringBuilder sb = new StringBuilder();
      for (int inicio = 0; inicio < paginas; inicio += LOTE_FOJAS) {
        int fin = Math.min(paginas, inicio + LOTE_FOJAS);
        byte[] lote = copiarRango(doc, inicio, fin);
        String texto = ocrSeguro(lote, "application/pdf", "fojas " + (inicio + 1) + "-" + fin);
        sb.append("\n--- FOJAS ").append(inicio + 1).append('-').append(fin).append(" ---\n");
        if (texto != null) {
          sb.append(texto);
        }
      }
      return sb.toString().trim();
    } catch (IOException e) {
      LOG.warn("No se pudo partir el PDF: {}", e.getMessage());
      return ocrSeguro(bytes, "application/pdf", "pdf-completo");
    }
  }

  private static String unirFojas(PDDocument doc) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    StringBuilder sb = new StringBuilder();
    int paginas = doc.getNumberOfPages();
    for (int i = 1; i <= paginas; i++) {
      stripper.setStartPage(i);
      stripper.setEndPage(i);
      sb.append("\n--- FOJA ").append(i).append(" ---\n");
      String texto = stripper.getText(doc);
      if (texto != null) {
        sb.append(texto.trim());
      }
    }
    return sb.toString().trim();
  }

  private static byte[] copiarRango(PDDocument origen, int desde, int hasta) throws IOException {
    try (PDDocument lote = new PDDocument()) {
      for (int i = desde; i < hasta; i++) {
        PDPage page = origen.getPage(i);
        lote.importPage(page);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      lote.save(out);
      return out.toByteArray();
    }
  }

  private String ocrSeguro(byte[] bytes, String mime, String etiqueta) {
    if (bytes == null || bytes.length == 0 || !ocr.isConfigured()) {
      return "";
    }
    try {
      String texto = ocr.extraerTexto(bytes, mime);
      return texto == null ? "" : texto;
    } catch (Exception e) {
      LOG.warn("OCR Azure falló {}: {}", etiqueta, e.getMessage());
      return "";
    }
  }

  static boolean esPdf(String mime, String nombre, byte[] bytes) {
    String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
    if (m.contains("pdf") || n.endsWith(".pdf")) {
      return true;
    }
    return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
  }
}
