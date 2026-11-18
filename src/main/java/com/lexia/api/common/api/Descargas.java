package com.lexia.api.common.api;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;

/** Cabeceras de descarga. */
public final class Descargas {

  private Descargas() {}

  /**
   * Content-Disposition de adjunto con el nombre escapado (comillas, barras, no ASCII vía
   * filename*). Concatenar el nombre a mano permitía romper la cabecera con un nombre que lleva
   * comillas.
   */
  public static String adjunto(String nombre) {
    String seguro = nombre == null || nombre.isBlank() ? "archivo" : nombre;
    return ContentDisposition.attachment().filename(seguro, StandardCharsets.UTF_8).build().toString();
  }
}
