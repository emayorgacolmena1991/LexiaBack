package com.lexia.api.modules.expedientes.escrituracion.masivo;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestaWorkerExecutorConfig {

  static final int HILOS_DEFAULT = 5;
  static final int HILOS_MAX = 20;

  static int acotarHilos(int hilos) {
    return Math.max(1, Math.min(hilos, HILOS_MAX));
  }

  @Bean
  int ingestaWorkerHilos(@Value("${lexia.ingesta.worker.hilos:5}") int hilos) {
    return acotarHilos(hilos);
  }

  @Bean(destroyMethod = "shutdown")
  public ExecutorService ingestaWorkerExecutor(int ingestaWorkerHilos) {
    ExecutorService pool =
        Executors.newFixedThreadPool(
            ingestaWorkerHilos,
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