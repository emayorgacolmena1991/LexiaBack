package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.storage.ArchivoStorage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Bytes de escrituración sobre {@link ArchivoStorage}. La fila guarda la clave en {@code
 * document_version.storage_key}; las filas anteriores conservan su ruta absoluta heredada.
 */
@Component
public class ExpedienteDocumentoStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ExpedienteDocumentoStorage.class);

  private final ArchivoStorage archivos;

  public ExpedienteDocumentoStorage(ArchivoStorage archivos) {
    this.archivos = archivos;
  }

  /**
   * Versión de un documento del expediente. Cada versión tiene su clave: reemplazar nunca toca la
   * anterior.
   */
  public Stored guardarVersion(
      UUID tenantId,
      UUID caseId,
      UUID documentId,
      int versionNo,
      String fileName,
      String contentType,
      byte[] bytes) {
    String clave =
        tenantId
            + "/expedientes/"
            + caseId
            + "/"
            + documentId
            + "/v"
            + versionNo
            + "_"
            + UUID.randomUUID()
            + "_"
            + safeName(fileName);
    return guardar(clave, contentType, bytes);
  }

  public byte[] read(String storageKey) {
    return archivos.leer(storageKey);
  }

  /** Borra el archivo si la transacción en curso hace rollback; sin transacción no hace nada. */
  public void borrarSiHayRollback(String storageKey) {
    archivos.borrarSiHayRollback(storageKey);
  }

  /** Ver {@link ArchivoStorage#sustituirAlTerminar}. */
  public void sustituirAlTerminar(String nueva, String anterior) {
    archivos.sustituirAlTerminar(nueva, anterior);
  }

  /** Tamaño para listados: un archivo ausente o una clave rechazada no rompe el listado (0). */
  public long size(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      return 0;
    }
    try {
      return archivos.existe(storageKey) ? archivos.tamano(storageKey) : 0;
    } catch (ApiException ex) {
      LOG.warn("Storage.size FALLO key={}: {}", storageKey, ex.getMessage());
      return 0;
    }
  }

  /** Clave completa armada por el llamador ({@code {tenant}/...}); nunca sobrescribe. */
  public Stored guardar(String clave, String contentType, byte[] bytes) {
    String sha256;
    try {
      sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
    String guardada =
        archivos.guardar(clave, new ByteArrayInputStream(bytes), bytes.length, contentType);
    return new Stored(guardada, sha256);
  }

  public static String safeName(String fileName) {
    String raw = fileName == null || fileName.isBlank() ? "archivo" : fileName.trim();
    String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
    if (cleaned.length() > 80) {
      cleaned = cleaned.substring(cleaned.length() - 80);
    }
    return cleaned.toLowerCase(Locale.ROOT);
  }

  /** @param clave lo que se persiste en la fila ({@code storage_key} / {@code *_storage_ref}). */
  public record Stored(String clave, String sha256) {}
}
