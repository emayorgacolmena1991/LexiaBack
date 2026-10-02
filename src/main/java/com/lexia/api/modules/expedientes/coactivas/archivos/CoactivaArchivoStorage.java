package com.lexia.api.modules.expedientes.coactivas.archivos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Guarda archivos de coactivas en disco: {@code <root>/<tenant>/<yyyy>/<MM>/<uuid>_<nombre>}. */
@Component
public class CoactivaArchivoStorage {

  private final Path root;

  public CoactivaArchivoStorage(@Value("${lexia.coactivas.storage-dir:./data/coactivas}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public StoredFile store(UUID tenantId, UUID archivoId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("El archivo está vacío.");
    }
    LocalDate today = LocalDate.now();
    Path dir =
        root.resolve(tenantId.toString())
            .resolve(String.valueOf(today.getYear()))
            .resolve(String.format("%02d", today.getMonthValue()));
    Path target = dir.resolve(archivoId + "_" + CoactivaTexto.safeFileName(file.getOriginalFilename()));
    try {
      Files.createDirectories(dir);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return new StoredFile(target.toString(), HexFormat.of().formatHex(digest.digest()), file.getSize());
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "COA_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
  }

  public StoredFile storeBytes(UUID tenantId, UUID archivoId, String fileName, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw ApiException.badRequest("El archivo está vacío.");
    }
    LocalDate today = LocalDate.now();
    Path dir =
        root.resolve(tenantId.toString())
            .resolve(String.valueOf(today.getYear()))
            .resolve(String.format("%02d", today.getMonthValue()));
    Path target = dir.resolve(archivoId + "_" + CoactivaTexto.safeFileName(fileName));
    try {
      Files.createDirectories(dir);
      Files.write(target, bytes);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new StoredFile(target.toString(), HexFormat.of().formatHex(digest.digest(bytes)), bytes.length);
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "COA_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
  }

  public byte[] read(CoactivaArchivo archivo) {
    Path path = Path.of(archivo.getStoragePath()).toAbsolutePath().normalize();
    if (!path.startsWith(root) || !Files.exists(path)) {
      throw ApiException.notFound("Archivo no disponible en el almacenamiento.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "COA_STORAGE_ERROR", "No se pudo leer el archivo.");
    }
  }

  public record StoredFile(String path, String sha256, long size) {}
}
