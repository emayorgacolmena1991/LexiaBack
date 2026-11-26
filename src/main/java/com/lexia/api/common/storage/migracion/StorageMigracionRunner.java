package com.lexia.api.common.storage.migracion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("migrar-storage")
public class StorageMigracionRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(StorageMigracionRunner.class);

  private final StorageMigracionService migracion;
  private final ApplicationContext context;

  public StorageMigracionRunner(StorageMigracionService migracion, ApplicationContext context) {
    this.migracion = migracion;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int codigo = 0;
    try {
      codigo = migracion.ejecutar();
    } catch (Exception ex) {
      LOG.error("Migración storage falló: {}", ex.toString());
      codigo = 2;
    } finally {
      int salida = codigo;
      new Thread(
              () -> {
                org.springframework.boot.SpringApplication.exit(context, () -> salida);
                System.exit(salida);
              },
              "migrar-storage-exit")
          .start();
    }
  }
}