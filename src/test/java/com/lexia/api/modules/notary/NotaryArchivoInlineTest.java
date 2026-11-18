package com.lexia.api.modules.notary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** S3: el MIME guardado puede venir del cliente; HTML/SVG nunca se sirve inline. */
class NotaryArchivoInlineTest {

  @Test
  void htmlSeDescargaComoBinario() {
    ResponseEntity<byte[]> r = servir("informe.png", "text/html");

    assertEquals(MediaType.APPLICATION_OCTET_STREAM, r.getHeaders().getContentType());
    assertTrue(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
    assertEquals("nosniff", r.getHeaders().getFirst("X-Content-Type-Options"));
  }

  @Test
  void svgSeDescargaComoBinario() {
    ResponseEntity<byte[]> r = servir("logo.svg", "image/svg+xml");

    assertEquals(MediaType.APPLICATION_OCTET_STREAM, r.getHeaders().getContentType());
    assertTrue(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
  }

  @Test
  void pdfEImagenesSiguenInline() {
    ResponseEntity<byte[]> pdf = servir("escritura.pdf", "application/pdf");
    ResponseEntity<byte[]> png = servir("logo.png", "image/png");

    assertEquals(MediaType.APPLICATION_PDF, pdf.getHeaders().getContentType());
    assertTrue(pdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
    assertEquals(MediaType.IMAGE_PNG, png.getHeaders().getContentType());
    assertTrue(png.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
  }

  @Test
  void docxSigueComoAdjunto() {
    ResponseEntity<byte[]> r = servir("minuta.docx", "application/octet-stream");

    assertTrue(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
  }

  private static ResponseEntity<byte[]> servir(String nombre, String mime) {
    return NotaryController.archivo(
        new NotaryDtos.RemisionDocumentContent(
            nombre, mime, "contenido".getBytes(StandardCharsets.UTF_8)));
  }
}
