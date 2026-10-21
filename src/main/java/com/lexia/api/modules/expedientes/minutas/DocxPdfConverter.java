package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.common.api.ApiException;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
  private final Path workDir;

  public DocxPdfConverter(
      @Value("${lexia.minutas.soffice-path:}") String sofficePath,
      @Value("${lexia.minutas.storage-dir:./data/minutas}") String storageDir) {
    this.sofficePath = sofficePath == null ? "" : sofficePath.trim();
    this.workDir = Path.of(storageDir).toAbsolutePath().normalize().resolve("preview-tmp");
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
    Files.createDirectories(workDir);
    Path dir = Files.createTempDirectory(workDir, "pv-");
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
      borrar(outPdf);
      borrar(in);
      borrar(dir);
    }
  }

  private static void borrar(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // temporal: se limpia en el siguiente arranque si quedó
    }
  }
}
