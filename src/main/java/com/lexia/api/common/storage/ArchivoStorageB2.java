package com.lexia.api.common.storage;

import com.lexia.api.common.api.ApiException;
import java.io.InputStream;
import java.net.URI;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Backblaze B2 por su API compatible con S3. El bucket es privado y el cifrado en reposo es
 * SSE-B2 (AES-256). La clave guardada en la base sigue siendo la ruta relativa; la descarga
 * la hace la API autenticada, no una URL pública.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "lexia.storage", name = "provider", havingValue = "b2")
public class ArchivoStorageB2 implements ArchivoStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ArchivoStorageB2.class);
  private static final Pattern UNIDAD_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

  private final S3Client s3;
  private final String bucket;
  private final ArchivoStorageLocal legado;

  public ArchivoStorageB2(
      @Value("${lexia.storage.b2.key-id}") String keyId,
      @Value("${lexia.storage.b2.application-key}") String applicationKey,
      @Value("${lexia.storage.b2.bucket}") String bucket,
      @Value("${lexia.storage.b2.endpoint}") String endpoint,
      @Value("${lexia.storage.b2.region:us-east-005}") String region,
      ArchivoStorageLocal legado) {
    if (keyId == null || keyId.isBlank() || applicationKey == null || applicationKey.isBlank()
        || bucket == null || bucket.isBlank() || endpoint == null || endpoint.isBlank()) {
      throw new IllegalStateException("Falta la configuración de Backblaze B2 (lexia.storage.b2.*).");
    }
    this.bucket = bucket;
    this.legado = legado;
    this.s3 =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, applicationKey)))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .forcePathStyle(true)
            .build();
    try {
      this.s3.headBucket(b -> b.bucket(this.bucket));
    } catch (S3Exception ex) {
      LOG.warn("Storage B2 no responde status={}", ex.statusCode());
      throw new IllegalStateException("No se pudo conectar al bucket de Backblaze B2.");
    }
  }

  @Override
  public String guardar(String clave, InputStream contenido, long tamano, String contentType) {
    validarRelativa(clave);
    if (tamano < 0) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "DOC_STORAGE_ERROR",
          "No se pudo guardar el archivo.");
    }
    if (existeEnB2(clave)) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "STORAGE_KEY_EXISTS",
          "El archivo ya existe en el almacenamiento.");
    }
    String tipo = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(clave)
              .contentType(tipo)
              .contentLength(tamano)
              .serverSideEncryption(ServerSideEncryption.AES256)
              .build(),
          RequestBody.fromInputStream(contenido, tamano));
    } catch (S3Exception ex) {
      LOG.warn("Storage B2 guardar fallo status={}", ex.statusCode());
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo guardar el archivo.");
    }
    LOG.info("Storage B2 guardar OK key={} size={}", clave, tamano);
    return clave;
  }

  @Override
  public InputStream abrir(String clave) {
    if (esHeredada(clave)) {
      return legado.abrir(clave);
    }
    validarRelativa(clave);
    if (existeEnB2(clave)) {
      try {
        return s3.getObject(r -> r.bucket(bucket).key(clave));
      } catch (S3Exception ex) {
        LOG.warn("Storage B2 abrir fallo status={}", ex.statusCode());
        throw new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
      }
    }
    return legado.abrir(clave);
  }

  @Override
  public byte[] leer(String clave) {
    try (InputStream in = abrir(clave)) {
      return in.readAllBytes();
    } catch (java.io.IOException ex) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
    }
  }

  @Override
  public boolean existe(String clave) {
    if (clave == null || clave.isBlank()) {
      return false;
    }
    if (esHeredada(clave)) {
      return legado.existe(clave);
    }
    try {
      validarRelativa(clave);
    } catch (ApiException ex) {
      return false;
    }
    return existeEnB2(clave) || legado.existe(clave);
  }

  @Override
  public long tamano(String clave) {
    if (esHeredada(clave)) {
      return legado.tamano(clave);
    }
    validarRelativa(clave);
    if (existeEnB2(clave)) {
      try {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(clave).build()).contentLength();
      } catch (S3Exception ex) {
        throw new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
      }
    }
    return legado.tamano(clave);
  }

  @Override
  public void borrar(String clave) {
    if (clave == null || clave.isBlank()) {
      return;
    }
    if (!esHeredada(clave)) {
      validarRelativa(clave);
      try {
        s3.deleteObject(r -> r.bucket(bucket).key(clave));
      } catch (NoSuchKeyException ignored) {
        // Ya no está en el bucket.
      } catch (S3Exception ex) {
        if (ex.statusCode() != 404) {
          LOG.warn("Storage B2 borrar fallo status={}", ex.statusCode());
          throw new ApiException(
              HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo borrar el archivo.");
        }
      }
    }
    legado.borrar(clave);
  }

  private boolean existeEnB2(String clave) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(clave).build());
      return true;
    } catch (NoSuchKeyException ex) {
      return false;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        return false;
      }
      LOG.warn("Storage B2 existe fallo status={}", ex.statusCode());
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DOC_STORAGE_ERROR", "No se pudo leer el archivo.");
    }
  }

  private static void validarRelativa(String clave) {
    if (clave == null
        || clave.isBlank()
        || esHeredada(clave)
        || clave.indexOf('\0') >= 0
        || clave.contains("..")) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_KEY_INVALID", "Clave de almacenamiento no válida.");
    }
  }

  private static boolean esHeredada(String clave) {
    if (clave == null || clave.isBlank()) {
      return false;
    }
    return clave.startsWith("/")
        || clave.startsWith("\\")
        || clave.indexOf(':') >= 0
        || UNIDAD_WINDOWS.matcher(clave).find();
  }
}