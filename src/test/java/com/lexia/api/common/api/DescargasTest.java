package com.lexia.api.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

/** S15: el nombre no puede romper la cabecera Content-Disposition. */
class DescargasTest {

  @Test
  void comillasEnElNombreNoCierranElParametro() {
    String cabecera = Descargas.adjunto("minuta\"; filename=\"malo.html");

    ContentDisposition parsed = ContentDisposition.parse(cabecera);
    assertTrue(cabecera.startsWith("attachment"));
    assertEquals("minuta\"; filename=\"malo.html", parsed.getFilename());
  }

  @Test
  void nombreConTildesVaCodificado() {
    String cabecera = Descargas.adjunto("Escritura Peña.docx");

    assertTrue(cabecera.contains("filename*=UTF-8''"));
    assertEquals("Escritura Peña.docx", ContentDisposition.parse(cabecera).getFilename());
  }

  @Test
  void nombreVacioUsaUnoGenerico() {
    assertFalse(Descargas.adjunto(null).contains("null"));
    assertEquals("archivo", ContentDisposition.parse(Descargas.adjunto(" ")).getFilename());
  }
}
