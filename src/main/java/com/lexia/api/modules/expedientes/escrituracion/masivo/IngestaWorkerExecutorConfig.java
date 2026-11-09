package com.lexia.api.modules.expedientes.escrituracion.masivo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestaWorkerExecutorConfig {

  static final int HILOS = 5;

  @Bean(destroyMethod = "shutdown")
  public ExecutorService ingestaWorkerExecutor() {
    return Executors.newFixedThreadPool(
        HILOS,
        r -> {
          Thread t = new Thread(r, "ingesta-worker");
          t.setDaemon(true);
          return t;
        });
  }
}
