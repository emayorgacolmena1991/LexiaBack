package com.lexia.api.common.storage;

import java.io.InputStream;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Almacenamiento de archivos por clave relativa ({@code tenant/...}). La implementación se elige
 * con {@code lexia.storage.provider}. Las filas guardan la clave tal cual la devuelve {@link
 * #guardar}.
 */
public interface ArchivoStorage {

  /**
   * Guarda el contenido bajo {@code clave}. Nunca sobrescribe: si la clave ya existe, lanza.
   *
   * @param tamano bytes esperados; {@code < 0} si no se conoce.
   * @return la clave guardada.
   */
  String guardar(String clave, InputStream contenido, long tamano, String contentType);

  /** El llamador cierra el stream. */
  InputStream abrir(String clave);

  byte[] leer(String clave);

  boolean existe(String clave);

  long tamano(String clave);

  /** Lanza si no se pudo borrar; una clave que ya no existe no es un fallo. */
  void borrar(String clave);

  /**
   * Borra el archivo si la transacción en curso hace rollback; sin transacción no hace nada. Un
   * fallo al borrar se registra: la limpieza no puede cambiar el resultado de la operación.
   */
  default void borrarSiHayRollback(String clave) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_ROLLED_BACK) {
              return;
            }
            try {
              borrar(clave);
            } catch (RuntimeException ex) {
              LoggerFactory.getLogger(ArchivoStorage.class)
                  .warn("Storage.borrarSiHayRollback FALLO key={}: {}", clave, ex.getMessage());
            }
          }
        });
  }
}
