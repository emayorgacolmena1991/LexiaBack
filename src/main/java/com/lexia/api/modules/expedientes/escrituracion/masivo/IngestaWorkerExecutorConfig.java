package com.lexia.api.modules.expedientes.escrituracion.masivo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestaWorkerExecutorConfig {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService ingestaWorkerExecutor() {
    return Executors.newFixedThreadPool(
        2,
        r -> {
          Thread t = new Thread(r, "ingesta-worker");
          t.setDaemon(true);
          return t;
        });
  }
}
