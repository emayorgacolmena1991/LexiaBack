package com.lexia.api.common.storage.migracion;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolución de rutas en disco (misma lógica que {@link
 * com.lexia.api.common.storage.ArchivoStorageLocal}), para la migración a B2.
 */
public final class StorageMigracionPaths {

  private static final Pattern UNIDAD_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

  private final Path root;
  private final List<Path> heredadas;

  public StorageMigracionPaths(Path root, Path... heredadas) {
    this.root = root.toAbsolutePath().normalize();
    this.heredadas = Arrays.stream(heredadas).map(p -> p.toAbsolutePath().normalize()).toList();
  }

  /** {@code null} si no hay archivo local. */
  public Path resolverArchivo(String clave) {
    if (clave == null || clave.isBlank()) {
      return null;
    }
    return esHeredada(clave) ? resolverHeredada(clave) : resolverNuevaSiExiste(clave);
  }

  /** Clave B2/BD objetivo: relativa con {@code /}, sin cambiar filas que ya son relativas válidas. */
  public String claveObjetivo(String valorBd, Path archivo) {
    if (valorBd == null || valorBd.isBlank() || archivo == null) {
      return null;
    }
    if (!esHeredada(valorBd)) {
      return normalizarClave(valorBd);
    }
    if (archivo.startsWith(root)) {
      return normalizarClave(root.relativize(archivo).toString());
    }
    String cola = colaDesdePrimerUuid(valorBd);
    if (cola != null) {
      return normalizarClave(cola);
    }
    Path bajoRaiz = root.relativize(archivo);
    if (!bajoRaiz.startsWith("..")) {
      return normalizarClave(bajoRaiz.toString());
    }
    return null;
  }

  public static boolean esHeredada(String clave) {
    if (clave == null || clave.isBlank()) {
      return false;
    }
    if (clave.startsWith("/") || clave.startsWith("\\") || UNIDAD_WINDOWS.matcher(clave).find()) {
      return true;
    }
    try {
      return Path.of(clave).isAbsolute();
    } catch (InvalidPathException ex) {
      return false;
    }
  }

  private Path resolverNuevaSiExiste(String clave) {
    try {
      Path path = root.resolve(clave).normalize();
      if (!path.startsWith(root) || path.equals(root)) {
        return null;
      }
      return Files.isRegularFile(path) ? path : null;
    } catch (InvalidPathException ex) {
      return null;
    }
  }

  private Path resolverHeredada(String clave) {
    String[] partes = clave.replace('\\', '/').split("/");
    if (partes.length == 0 || Arrays.asList(partes).contains("..")) {
      return null;
    }
    try {
      Path directa = Path.of(clave).toAbsolutePath().normalize();
      if (dentroDeHeredada(directa) && Files.isRegularFile(directa)) {
        return directa;
      }
    } catch (InvalidPathException ignored) {
      // Ruta de otro SO: se reubica.
    }
    for (int i = 0; i < partes.length; i++) {
      if (!esUuid(partes[i])) {
        continue;
      }
      Path reubicada = reubicar(String.join("/", Arrays.copyOfRange(partes, i, partes.length)));
      if (reubicada != null) {
        return reubicada;
      }
    }
    String archivo = partes[partes.length - 1];
    if (archivo.length() > 37 && archivo.charAt(36) == '_' && esUuid(archivo.substring(0, 36))) {
      return reubicar(archivo);
    }
    return null;
  }

  private Path reubicar(String cola) {
    for (Path carpeta : heredadas) {
      Path reubicada;
      try {
        reubicada = carpeta.resolve(cola).normalize();
      } catch (InvalidPathException ex) {
        continue;
      }
      if (reubicada.startsWith(carpeta)
          && !reubicada.equals(carpeta)
          && Files.isRegularFile(reubicada)) {
        return reubicada;
      }
    }
    return null;
  }

  private boolean dentroDeHeredada(Path path) {
    return heredadas.stream().anyMatch(c -> path.startsWith(c) && !path.equals(c));
  }

  private static String colaDesdePrimerUuid(String clave) {
    String[] partes = clave.replace('\\', '/').split("/");
    for (int i = 0; i < partes.length; i++) {
      if (esUuid(partes[i])) {
        return String.join("/", Arrays.copyOfRange(partes, i, partes.length));
      }
    }
    String archivo = partes[partes.length - 1];
    if (archivo.length() > 37 && archivo.charAt(36) == '_' && esUuid(archivo.substring(0, 36))) {
      return archivo;
    }
    return null;
  }

  private static boolean esUuid(String segmento) {
    try {
      UUID.fromString(segmento);
      return segmento.length() == 36;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static String normalizarClave(String clave) {
    return clave.replace('\\', '/');
  }
}