package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bytes de escrituración en disco. La fila vive en {@code document_version.storage_key}. */
@Component
public class ExpedienteDocumentoStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ExpedienteDocumentoStorage.class);

  private final Path root;

  public ExpedienteDocumentoStorage(
      @Value("${lexia.expedientes.storage-dir:./data/expedientes}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public Stored write(UUID tenantId, UUID caseId, UUID documentId, String fileName, byte[] bytes) {
    return writeEnCarpeta(tenantId, caseId.toString(), documentId, fileName, bytes);
  }

  /** Archivo del tenant que no pertenece a un expediente, como el logo de una notaría. */
  public Stored writeEnCarpeta(
      UUID tenantId, String carpeta, UUID documentId, String fileName, byte[] bytes) {
    String safe = safeName(fileName);
    Path dir = root.resolve(tenantId.toString()).resolve(carpeta);
    Path target = dir.resolve(documentId + "_" + safe);
    try {
      Files.createDirectories(dir);
      Files.write(target, bytes);
      LOG.info("Storage.write OK path={} size={} bytes", target, bytes.length);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new Stored(target.toString(), HexFormat.of().formatHex(digest.digest(bytes)));
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
  }

  public byte[] read(String storageKey) {
    Path path = ubicar(storageKey);
    if (path == null) {
      LOG.warn("Storage.read FALLO key={} NO EXISTE", storageKey);
      throw ApiException.notFound("Archivo no disponible en el almacenamiento.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
    }
  }

  /** Limpieza tras un rollback: un fallo aquí no puede cambiar el resultado de la operación. */
  public void delete(String storageKey) {
    Path path = ubicar(storageKey);
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      LOG.warn("Storage.delete FALLO key={}: {}", storageKey, ex.getMessage());
    }
  }

  public long size(String storageKey) {
    Path path = ubicar(storageKey);
    if (path == null) {
      return 0;
    }
    try {
      return Files.size(path);
    } catch (IOException ex) {
      return 0;
    }
  }

  /**
   * {@code storage_key} es la ruta absoluta del equipo que subió el archivo. Si la raíz de este
   * proceso es otra (otro equipo, otro cwd o {@code LEXIA_EXPEDIENTES_DIR}), se busca el mismo
   * tramo {@code <tenant>/<case>/<archivo>} bajo {@link #root}.
   */
  Path ubicar(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      return null;
    }
    try {
      Path directa = Path.of(storageKey).toAbsolutePath().normalize();
      if (directa.startsWith(root) && Files.isRegularFile(directa)) {
        return directa;
      }
    } catch (InvalidPathException ignored) {
      // Ruta de otro sistema operativo: se reubica abajo.
    }
    String[] partes = storageKey.replace('\\', '/').split("/");
    for (int i = 0; i < partes.length; i++) {
      try {
        UUID.fromString(partes[i]);
      } catch (IllegalArgumentException ex) {
        continue;
      }
      Path reubicada =
          root.resolve(String.join("/", Arrays.copyOfRange(partes, i, partes.length))).normalize();
      if (reubicada.startsWith(root) && Files.isRegularFile(reubicada)) {
        return reubicada;
      }
    }
    return null;
  }

  private static String safeName(String fileName) {
    String raw = fileName == null || fileName.isBlank() ? "archivo" : fileName.trim();
    String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
    if (cleaned.length() > 80) {
      cleaned = cleaned.substring(cleaned.length() - 80);
    }
    return cleaned.toLowerCase(Locale.ROOT);
  }

  public record Stored(String path, String sha256) {}
}
