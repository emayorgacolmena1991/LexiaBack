package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Pipeline de previsualización .docx → .pdf (TICKET-INT-102 [B]).
 *
 * <p>Si {@code lexia.minutas.soffice-path} apunta a un ejecutable de LibreOffice se usa en modo
 * headless (máxima fidelidad de estilos, tablas y márgenes). En caso contrario, o si LibreOffice
 * falla, se convierte en Java puro con xdocreport (Apache POI + iText), suficiente para la vista
 * previa en el navegador. El .docx descargable nunca pasa por este conversor.
 */
@Service
public class DocxPdfConverter {

  private static final Logger LOG = LoggerFactory.getLogger(DocxPdfConverter.class);
  private static final long SOFFICE_TIMEOUT_SEG = 90;

  private final String sofficePath;

  public DocxPdfConverter(@Value("${lexia.minutas.soffice-path:}") String sofficePath) {
    this.sofficePath = sofficePath == null ? "" : sofficePath.trim();
  }

  public byte[] toPdf(byte[] docx) {
    if (docx == null || docx.length == 0) {
      throw ApiException.badRequest("No hay documento .docx para previsualizar.");
    }
    if (StringUtils.hasText(sofficePath)) {
      try {
        return conLibreOffice(docx);
      } catch (Exception e) {
        LOG.warn("LibreOffice no pudo convertir la minuta ({}); se usa el conversor Java", e.getMessage());
      }
    }
    return conXdocreport(docx);
  }

  byte[] conXdocreport(byte[] docx) {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PdfConverter.getInstance().convert(doc, out, PdfOptions.create());
      byte[] pdf = out.toByteArray();
      LOG.info("Minuta convertida a PDF (xdocreport) bytes={}", pdf.length);
      return pdf;
    } catch (Exception e) {
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOG.error("Error convirtiendo minuta a PDF: {}", detail, e);
      throw ApiException.badRequest("No se pudo generar la vista previa en PDF: " + detail);
    }
  }

  private byte[] conLibreOffice(byte[] docx) throws IOException, InterruptedException {
    // Temporal del sistema: nada de la conversión queda bajo las carpetas de almacenamiento.
    Path dir = Files.createTempDirectory("lexia-pv-");
    Path in = dir.resolve("minuta.docx");
    Path outPdf = dir.resolve("minuta.pdf");
    try {
      Files.write(in, docx);
      Process p =
          new ProcessBuilder(
                  List.of(
                      sofficePath,
                      "--headless",
                      "--norestore",
                      "--convert-to",
                      "pdf",
                      "--outdir",
                      dir.toString(),
                      in.toString()))
              .redirectErrorStream(true)
              .start();
      if (!p.waitFor(SOFFICE_TIMEOUT_SEG, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("timeout de LibreOffice");
      }
      if (p.exitValue() != 0 || !Files.isRegularFile(outPdf)) {
        throw new IOException("LibreOffice exit=" + p.exitValue());
      }
      byte[] pdf = Files.readAllBytes(outPdf);
      LOG.info("Minuta convertida a PDF (LibreOffice) bytes={}", pdf.length);
      return pdf;
    } finally {
      borrarDirectorio(dir);
    }
  }

  /** LibreOffice puede dejar otros archivos (bloqueos, perfiles): se borra todo el directorio. */
  static void borrarDirectorio(Path dir) {
    try (Stream<Path> rutas = Files.walk(dir)) {
      rutas.sorted(Comparator.reverseOrder())
          .forEach(
              ruta -> {
                try {
                  Files.deleteIfExists(ruta);
                } catch (IOException ex) {
                  LOG.warn("No se pudo borrar el temporal {}: {}", ruta, ex.getMessage());
                }
              });
    } catch (IOException ex) {
      LOG.warn("No se pudo recorrer el temporal {}: {}", dir, ex.getMessage());
    }
  }
}
