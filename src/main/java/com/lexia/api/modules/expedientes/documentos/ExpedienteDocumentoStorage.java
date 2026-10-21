package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bytes de escrituración en disco. La fila vive en {@code document_version.storage_key}. */
@Component
public class ExpedienteDocumentoStorage {

  private final Path root;

  public ExpedienteDocumentoStorage(
      @Value("${lexia.expedientes.storage-dir:./data/expedientes}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public Stored write(UUID tenantId, UUID caseId, UUID documentId, String fileName, byte[] bytes) {
    String safe = safeName(fileName);
    Path dir = root.resolve(tenantId.toString()).resolve(caseId.toString());
    Path target = dir.resolve(documentId + "_" + safe);
    try {
      Files.createDirectories(dir);
      Files.write(target, bytes);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new Stored(target.toString(), HexFormat.of().formatHex(digest.digest(bytes)));
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
  }

  public byte[] read(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      throw ApiException.notFound("Archivo no disponible en el almacenamiento.");
    }
    Path path = Path.of(storageKey).toAbsolutePath().normalize();
    if (!path.startsWith(root) || !Files.exists(path)) {
      throw ApiException.notFound("Archivo no disponible en el almacenamiento.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
    }
  }

  public long size(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      return 0;
    }
    Path path = Path.of(storageKey).toAbsolutePath().normalize();
    if (!path.startsWith(root) || !Files.exists(path)) {
      return 0;
    }
    try {
      return Files.size(path);
    } catch (IOException ex) {
      return 0;
    }
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
