package com.lexia.api.common.storage.migracion;

public final class StorageMigracionResumen {

  public int subidos;
  public int yaEstaban;
  public int actualizadas;
  public int faltantes;
  public int errores;
  public int dryRun;
  public long bytesSubidos;

  void acumular(StorageMigracionProcesador.Salida salida) {
    switch (salida.resultado()) {
      case SUBIDO -> {
        subidos++;
        bytesSubidos += salida.bytesSubidos();
      }
      case YA_ESTABA -> yaEstaban++;
      case ACTUALIZADA -> actualizadas++;
      case FALTANTE -> faltantes++;
      case ERROR -> errores++;
      case DRY_RUN -> dryRun++;
    }
  }
}