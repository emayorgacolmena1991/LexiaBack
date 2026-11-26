package com.lexia.api.common.storage.migracion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Lógica por fila, testeable sin Spring. */
public final class StorageMigracionProcesador {

  private StorageMigracionProcesador() {}

  public record Fila(String id, String referencia, String hash, String contentType) {}

  public record Salida(
      StorageMigracionResultado resultado,
      String claveAntes,
      String claveDespues,
      String detalle,
      long bytesSubidos) {}

  public static Salida procesar(
      Fila fila,
      StorageMigracionPaths paths,
      StorageMigracionObjectStore bucket,
      boolean dryRun) {
    String antes = fila.referencia();
    if (antes == null || antes.isBlank()) {
      return new Salida(StorageMigracionResultado.FALTANTE, antes, null, "referencia vacía", 0);
    }

    Path archivo = paths.resolverArchivo(antes);
    if (archivo == null || !Files.isRegularFile(archivo)) {
      return new Salida(StorageMigracionResultado.FALTANTE, antes, null, "archivo no en disco", 0);
    }

    String clave = paths.claveObjetivo(antes, archivo);
    if (clave == null || clave.isBlank() || clave.contains("..")) {
      return new Salida(StorageMigracionResultado.ERROR, antes, null, "clave objetivo inválida", 0);
    }

    long localSize;
    try {
      localSize = Files.size(archivo);
    } catch (IOException ex) {
      return new Salida(StorageMigracionResultado.ERROR, antes, clave, "no se pudo leer tamaño", 0);
    }

    String hashEsperado = normalizarHash(fila.hash());
    if (hashEsperado != null) {
      String localHash = sha256Archivo(archivo);
      if (!hashEsperado.equals(localHash)) {
        return new Salida(
            StorageMigracionResultado.ERROR,
            antes,
            clave,
            "sha256 no coincide",
            0);
      }
    }

    boolean heredada = StorageMigracionPaths.esHeredada(antes);
    boolean mismaClave = Objects.equals(normalizar(antes), normalizar(clave));

    try {
      if (bucket.existe(clave)) {
        long remoto = bucket.tamano(clave);
        if (remoto != localSize) {
          return new Salida(
              StorageMigracionResultado.ERROR,
              antes,
              clave,
              "tamaño remoto distinto al local",
              0);
        }
        if (dryRun) {
          return dryRunSalida(antes, clave, heredada, mismaClave, "ya en bucket");
        }
        if (heredada && !mismaClave) {
          return new Salida(StorageMigracionResultado.ACTUALIZADA, antes, clave, "solo actualiza fila", 0);
        }
        return new Salida(StorageMigracionResultado.YA_ESTABA, antes, clave, "objeto en bucket", 0);
      }
    } catch (RuntimeException ex) {
      return new Salida(StorageMigracionResultado.ERROR, antes, clave, ex.getMessage(), 0);
    }

    if (dryRun) {
      return dryRunSalida(antes, clave, heredada, mismaClave, "subiría " + localSize + " bytes");
    }

    try {
      bucket.subir(clave, archivo, fila.contentType());
      if (!bucket.existe(clave) || bucket.tamano(clave) != localSize) {
        return new Salida(
            StorageMigracionResultado.ERROR,
            antes,
            clave,
            "verificación post-subida falló",
            0);
      }
    } catch (RuntimeException ex) {
      return new Salida(StorageMigracionResultado.ERROR, antes, clave, ex.getMessage(), 0);
    }

    if (heredada && !mismaClave) {
      return new Salida(StorageMigracionResultado.ACTUALIZADA, antes, clave, "subido y clave normalizada", localSize);
    }
    return new Salida(StorageMigracionResultado.SUBIDO, antes, clave, "subido", localSize);
  }

  private static Salida dryRunSalida(
      String antes, String clave, boolean heredada, boolean mismaClave, String detalle) {
    if (heredada && !mismaClave) {
      return new Salida(StorageMigracionResultado.DRY_RUN, antes, clave, "dry-run: " + detalle + " + update", 0);
    }
    return new Salida(StorageMigracionResultado.DRY_RUN, antes, clave, "dry-run: " + detalle, 0);
  }

  public static boolean requiereUpdate(Salida salida) {
    return salida.resultado() == StorageMigracionResultado.ACTUALIZADA
        || (salida.resultado() == StorageMigracionResultado.DRY_RUN
            && salida.claveDespues() != null
            && !Objects.equals(normalizar(salida.claveAntes()), normalizar(salida.claveDespues())));
  }

  private static String normalizar(String clave) {
    if (clave == null) {
      return null;
    }
    return clave.replace('\\', '/');
  }

  private static String normalizarHash(String hash) {
    if (hash == null || hash.isBlank()) {
      return null;
    }
    return hash.trim().toLowerCase(Locale.ROOT);
  }

  private static String sha256Archivo(Path archivo) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(archivo);
          DigestInputStream din = new DigestInputStream(in, digest)) {
        byte[] buf = new byte[8192];
        while (din.read(buf) != -1) {
          // vacía el stream
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("sha256 local", ex);
    }
  }
}