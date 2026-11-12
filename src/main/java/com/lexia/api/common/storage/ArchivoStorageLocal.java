package com.lexia.api.common.storage;

import com.lexia.api.common.api.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Disco local bajo {@code lexia.storage.local-root}. Las claves nuevas son relativas a esa raíz.
 * Las filas anteriores guardan rutas absolutas: se resuelven solo dentro de las carpetas heredadas
 * ({@code lexia.expedientes|minutas|coactivas.storage-dir}), directamente o reubicando su cola.
 */
@Component
@ConditionalOnProperty(
    prefix = "lexia.storage",
    name = "provider",
    havingValue = "local",
    matchIfMissing = true)
public class ArchivoStorageLocal implements ArchivoStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ArchivoStorageLocal.class);
  private static final Pattern UNIDAD_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

  private final Path root;
  private final List<Path> heredadas;

  @Autowired
  public ArchivoStorageLocal(
      @Value("${lexia.storage.local-root:./data}") String root,
      @Value("${lexia.expedientes.storage-dir:./data/expedientes}") String expedientes,
      @Value("${lexia.minutas.storage-dir:./data/minutas}") String minutas,
      @Value("${lexia.coactivas.storage-dir:./data/coactivas}") String coactivas) {
    this(Path.of(root), Path.of(expedientes), Path.of(minutas), Path.of(coactivas));
  }

  public ArchivoStorageLocal(Path root, Path... heredadas) {
    this.root = absoluta(root);
    this.heredadas = Arrays.stream(heredadas).map(ArchivoStorageLocal::absoluta).toList();
  }

  @Override
  public String guardar(String clave, InputStream contenido, long tamano, String contentType) {
    Path destino = resolverNueva(clave);
    try {
      Files.createDirectories(destino.getParent());
    } catch (IOException ex) {
      throw errorIo("No se pudo guardar el archivo.", ex);
    }
    long escritos;
    try (OutputStream out =
        Files.newOutputStream(destino, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      escritos = contenido.transferTo(out);
    } catch (FileAlreadyExistsException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "STORAGE_KEY_EXISTS",
          "El archivo ya existe en el almacenamiento.");
    } catch (IOException ex) {
      borrarParcial(destino);
      throw errorIo("No se pudo guardar el archivo.", ex);
    }
    if (tamano >= 0 && escritos != tamano) {
      borrarParcial(destino);
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "DOC_STORAGE_ERROR",
          "El archivo guardado no tiene el tamaño esperado.");
    }
    LOG.info("Storage.guardar OK key={} size={} bytes", clave, escritos);
    return clave;
  }

  @Override
  public InputStream abrir(String clave) {
    Path path = existente(clave);
    try {
      return Files.newInputStream(path);
    } catch (NoSuchFileException ex) {
      throw noDisponible(clave);
    } catch (IOException ex) {
      throw errorIo("No se pudo leer el archivo.", ex);
    }
  }

  @Override
  public byte[] leer(String clave) {
    Path path = existente(clave);
    try {
      return Files.readAllBytes(path);
    } catch (NoSuchFileException ex) {
      throw noDisponible(clave);
    } catch (IOException ex) {
      throw errorIo("No se pudo leer el archivo.", ex);
    }
  }

  @Override
  public boolean existe(String clave) {
    Path path = resolver(clave);
    return path != null && Files.isRegularFile(path);
  }

  @Override
  public long tamano(String clave) {
    Path path = existente(clave);
    try {
      return Files.size(path);
    } catch (NoSuchFileException ex) {
      throw noDisponible(clave);
    } catch (IOException ex) {
      throw errorIo("No se pudo leer el archivo.", ex);
    }
  }

  @Override
  public void borrar(String clave) {
    Path path = resolver(clave);
    if (path == null) {
      // Ruta heredada que no está en este servidor: no hay nada que borrar aquí.
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      throw errorIo("No se pudo borrar el archivo.", ex);
    }
  }

  private Path existente(String clave) {
    Path path = resolver(clave);
    if (path == null) {
      LOG.warn("Storage ruta heredada sin resolver en este servidor: {}", clave);
      throw new ApiException(
          HttpStatus.NOT_FOUND,
          "ARCHIVO_NO_DISPONIBLE",
          "El archivo no está disponible en este servidor.");
    }
    if (!Files.isRegularFile(path)) {
      throw noDisponible(clave);
    }
    return path;
  }

  /**
   * Lectura y borrado: clave relativa o ruta absoluta heredada.
   *
   * @return {@code null} si es una ruta heredada que no se puede resolver en este servidor.
   */
  private Path resolver(String clave) {
    return esHeredada(clave) ? resolverHeredada(clave) : resolverNueva(clave);
  }

  /** Clave relativa a la raíz: sin {@code ..}, sin raíz ni unidad, y que no salga de la raíz. */
  Path resolverNueva(String clave) {
    if (clave == null
        || clave.isBlank()
        || clave.startsWith("/")
        || clave.startsWith("\\")
        || clave.indexOf(':') >= 0
        || clave.indexOf('\0') >= 0) {
      throw claveInvalida(clave);
    }
    for (String segmento : clave.split("[/\\\\]")) {
      if (segmento.equals("..")) {
        throw claveInvalida(clave);
      }
    }
    Path path;
    try {
      path = root.resolve(clave).normalize();
    } catch (InvalidPathException ex) {
      throw claveInvalida(clave);
    }
    if (!path.startsWith(root) || path.equals(root)) {
      throw claveInvalida(clave);
    }
    return path;
  }

  /**
   * Ruta absoluta guardada por otro equipo, cwd o storage-dir. Se usa tal cual si existe dentro de
   * una carpeta heredada; si no, la cola desde un segmento UUID ({@code tenant/case/archivo}) se
   * busca bajo cada carpeta heredada actual. Con {@code ..} en la ruta no se reubica.
   */
  private Path resolverHeredada(String clave) {
    String[] partes = clave.replace('\\', '/').split("/");
    if (Arrays.asList(partes).contains("..")) {
      return null;
    }
    try {
      Path directa = Path.of(clave).toAbsolutePath().normalize();
      if (dentroDeHeredada(directa) && Files.isRegularFile(directa)) {
        return directa;
      }
    } catch (InvalidPathException ignored) {
      // Ruta de otro sistema operativo: se reubica abajo.
    }
    for (int i = 0; i < partes.length; i++) {
      if (!esUuid(partes[i])) {
        continue;
      }
      String cola = String.join("/", Arrays.copyOfRange(partes, i, partes.length));
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
    }
    return null;
  }

  private boolean dentroDeHeredada(Path path) {
    return heredadas.stream().anyMatch(c -> path.startsWith(c) && !path.equals(c));
  }

  /** Absoluta en este SO o con forma de absoluta de otro ({@code /...}, {@code \...}, {@code C:\...}). */
  private static boolean esHeredada(String clave) {
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

  private static boolean esUuid(String segmento) {
    try {
      UUID.fromString(segmento);
      return segmento.length() == 36;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static Path absoluta(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static void borrarParcial(Path destino) {
    try {
      Files.deleteIfExists(destino);
    } catch (IOException ex) {
      LOG.warn("Storage.guardar no pudo borrar el archivo parcial {}: {}", destino, ex.getMessage());
    }
  }

  private static ApiException claveInvalida(String clave) {
    LOG.warn("Storage clave rechazada: {}", clave);
    return new ApiException(
        HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_KEY_INVALID", "Clave de almacenamiento no válida.");
  }

  private static ApiException noDisponible(String clave) {
    LOG.warn("Storage.leer FALLO key={} NO EXISTE", clave);
    return ApiException.notFound("Archivo no disponible en el almacenamiento.");
  }

  private static ApiException errorIo(String mensaje, IOException ex) {
    LOG.warn("Storage IO: {}", ex.toString());
    return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", mensaje);
  }
}
