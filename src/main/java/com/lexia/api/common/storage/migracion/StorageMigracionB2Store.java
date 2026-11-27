package com.lexia.api.common.storage.migracion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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

@Component
@Profile("migrar-storage")
@ConditionalOnProperty(prefix = "lexia.storage", name = "provider", havingValue = "b2")
public class StorageMigracionB2Store implements StorageMigracionObjectStore {

  private static final Logger LOG = LoggerFactory.getLogger(StorageMigracionB2Store.class);

  private final S3Client s3;
  private final String bucket;

  public StorageMigracionB2Store(
      @Value("${lexia.storage.b2.key-id}") String keyId,
      @Value("${lexia.storage.b2.application-key}") String applicationKey,
      @Value("${lexia.storage.b2.bucket}") String bucket,
      @Value("${lexia.storage.b2.endpoint}") String endpoint,
      @Value("${lexia.storage.b2.region:us-east-005}") String region) {
    if (keyId == null
        || keyId.isBlank()
        || applicationKey == null
        || applicationKey.isBlank()
        || bucket == null
        || bucket.isBlank()
        || endpoint == null
        || endpoint.isBlank()) {
      throw new IllegalStateException("Falta configuración B2 para migrar-storage.");
    }
    this.bucket = bucket;
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
      LOG.warn("Migración B2: bucket no responde status={}", ex.statusCode());
      throw new IllegalStateException("No se pudo conectar al bucket B2.");
    }
    LOG.info("Migración B2 lista: bucket={}", bucket);
  }

  @Override
  public boolean existe(String clave) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(clave).build());
      return true;
    } catch (NoSuchKeyException ex) {
      return false;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        return false;
      }
      throw new IllegalStateException("Error comprobando objeto en B2: status=" + ex.statusCode());
    }
  }

  @Override
  public long tamano(String clave) {
    return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(clave).build()).contentLength();
  }

  @Override
  public void subir(String clave, Path archivo, String contentType) {
    if (existe(clave)) {
      throw new IllegalStateException("STORAGE_KEY_EXISTS");
    }
    String tipo =
        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    long bytes;
    try {
      bytes = Files.size(archivo);
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo leer tamaño local", ex);
    }
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(clave)
              .contentType(tipo)
              .contentLength(bytes)
              .serverSideEncryption(ServerSideEncryption.AES256)
              .build(),
          RequestBody.fromFile(archivo));
    } catch (S3Exception ex) {
      throw new IllegalStateException("Subida B2 falló: status=" + ex.statusCode(), ex);
    }
  }
}