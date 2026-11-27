package com.lexia.api.common.storage.migracion;

import java.nio.file.Path;

/** Acceso S3/B2 usado solo por la migración (sin sobrescribir objetos). */
public interface StorageMigracionObjectStore {

  boolean existe(String clave);

  long tamano(String clave);

  void subir(String clave, Path archivo, String contentType);
}