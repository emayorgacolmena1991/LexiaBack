package com.lexia.api.modules.expedientes.coactivas.archivos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.storage.ArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Archivos de coactivas sobre {@link ArchivoStorage}: {@code
 * {tenant}/coactivas/{yyyy}/{MM}/{archivoId}_{uuid}_{nombre}}. Nunca sobrescribe; las filas
 * anteriores conservan su ruta absoluta heredada.
 */
@Component
public class CoactivaArchivoStorage {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaArchivoStorage.class);

  private final ArchivoStorage archivos;

  public CoactivaArchivoStorage(ArchivoStorage archivos) {
    this.archivos = archivos;
  }

  /** Subida en streaming: el hash se calcula mientras se copia. */
  public StoredFile store(UUID tenantId, UUID archivoId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("El archivo está vacío.");
    }
    String clave = clave(tenantId, archivoId, file.getOriginalFilename());
    MessageDigest digest = sha256();
    try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
      archivos.guardar(clave, in, file.getSize(), file.getContentType());
    } catch (IOException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "COA_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
    archivos.borrarSiHayRollback(clave);
    return new StoredFile(clave, HexFormat.of().formatHex(digest.digest()), file.getSize());
  }

  public StoredFile storeBytes(UUID tenantId, UUID archivoId, String fileName, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw ApiException.badRequest("El archivo está vacío.");
    }
    String clave = clave(tenantId, archivoId, fileName);
    archivos.guardar(clave, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    archivos.borrarSiHayRollback(clave);
    return new StoredFile(clave, HexFormat.of().formatHex(sha256().digest(bytes)), bytes.length);
  }

  /** {@code false} si no hay ruta, el archivo no está en este servidor o la clave se rechaza. */
  public boolean disponible(CoactivaArchivo archivo) {
    String clave = archivo.getStoragePath();
    if (clave == null || clave.isBlank()) {
      return false;
    }
    try {
      return archivos.existe(clave);
    } catch (ApiException ex) {
      LOG.warn("Coactiva archivo={} clave rechazada: {}", archivo.getId(), ex.getMessage());
      return false;
    }
  }

  public byte[] read(CoactivaArchivo archivo) {
    String clave = archivo.getStoragePath();
    if (clave == null || clave.isBlank()) {
      throw ApiException.notFound("Archivo no disponible en el almacenamiento.");
    }
    return archivos.leer(clave);
  }

  static String clave(UUID tenantId, UUID archivoId, String nombre) {
    LocalDate hoy = LocalDate.now();
    return tenantId
        + "/coactivas/"
        + hoy.getYear()
        + "/"
        + String.format("%02d", hoy.getMonthValue())
        + "/"
        + archivoId
        + "_"
        + UUID.randomUUID()
        + "_"
        + CoactivaTexto.safeFileName(nombre);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "COA_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
  }

  /** @param path clave persistida en {@code storage_path}. */
  public record StoredFile(String path, String sha256, long size) {}
}
