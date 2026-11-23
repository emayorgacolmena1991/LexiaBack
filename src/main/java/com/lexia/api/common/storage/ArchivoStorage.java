package com.lexia.api.common.storage;

import java.io.InputStream;
import java.util.Objects;
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
    sustituirAlTerminar(clave, null);
  }

  /**
   * Archivo que reemplaza a otro en una fila: rollback borra {@code nueva}; commit borra {@code
   * anterior}. Sin transacción no hace nada. Si el borrado falla queda un huérfano (WARN): la fila ya
   * apunta al archivo correcto y no se pierde nada.
   */
  default void sustituirAlTerminar(String nueva, String anterior) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            String sobrante =
                status == STATUS_ROLLED_BACK
                    ? nueva
                    : status == STATUS_COMMITTED ? anterior : null;
            // Misma clave en ambas: es el archivo vigente, no se borra.
            if (sobrante == null || sobrante.isBlank() || Objects.equals(nueva, anterior)) {
              return;
            }
            try {
              borrar(sobrante);
            } catch (RuntimeException ex) {
              LoggerFactory.getLogger(ArchivoStorage.class)
                  .warn("Storage: no se pudo borrar {} (queda huérfano): {}", sobrante, ex.getMessage());
            }
          }
        });
  }
}
