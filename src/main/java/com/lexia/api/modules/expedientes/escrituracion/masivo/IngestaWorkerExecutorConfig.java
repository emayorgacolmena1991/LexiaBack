package com.lexia.api.modules.expedientes.escrituracion.masivo;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestaWorkerExecutorConfig {

  static final int HILOS = 5;

  @Bean(destroyMethod = "shutdown")
  public ExecutorService ingestaWorkerExecutor() {
    ExecutorService pool =
        Executors.newFixedThreadPool(
            HILOS,
            r -> {
              Thread t = new Thread(r, "ingesta-worker");
              t.setDaemon(true);
              return t;
            });
    // P0: executor.active / executor.queued / executor.completed {name=ingesta-worker}.
    new ExecutorServiceMetrics(pool, "ingesta-worker", Tags.empty()).bindTo(Metrics.globalRegistry);
    return pool;
  }
}
