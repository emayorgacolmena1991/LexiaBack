package com.lexia.api.config;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.tenancy.TenantContext;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

@Configuration
@EnableAsync
@EnableConfigurationProperties(BrevoProperties.class)
public class AsyncConfig {

  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    return wrapWithTenantAuth(newPool("lexia-async-", 4, 16, 100));
  }

  /** Pool paralelo para OCR Azure (batch documentos). */
  @Bean(name = "ocrExecutor")
  Executor ocrExecutor() {
    return wrapWithTenantAuth(newPool("AzureOCR-", 10, 20, 100));
  }

  /** Cola llena: la tarea corre en el hilo que la envía (frena al productor) en vez de fallar. */
  private static ThreadPoolTaskExecutor newPool(
      String prefix, int core, int max, int queueCapacity) {
    ThreadPoolTaskExecutor delegate = new ThreadPoolTaskExecutor();
    delegate.setThreadNamePrefix(prefix);
    delegate.setCorePoolSize(core);
    delegate.setMaxPoolSize(max);
    delegate.setQueueCapacity(queueCapacity);
    delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    delegate.initialize();
    // P0: executor.active / executor.queued / executor.pool.size {name=lexia-async|AzureOCR}.
    String nombre = prefix.endsWith("-") ? prefix.substring(0, prefix.length() - 1) : prefix;
    new ExecutorServiceMetrics(delegate.getThreadPoolExecutor(), nombre, Tags.empty())
        .bindTo(Metrics.globalRegistry);
    return delegate;
  }

  /**
   * Propaga auth y tenant del que envía. Al terminar restaura los del hilo que ejecuta: con
   * CallerRuns ese hilo es el del request, que no debe perder su contexto.
   */
  private static Executor wrapWithTenantAuth(ThreadPoolTaskExecutor delegate) {
    Executor secured = new DelegatingSecurityContextExecutor(delegate);
    return command -> {
      AuthPrincipal principal = AuthContext.get();
      UUID tenantId = TenantContext.getTenantId();
      secured.execute(
          () -> {
            AuthPrincipal principalPrevio = AuthContext.get();
            UUID tenantPrevio = TenantContext.getTenantId();
            try {
              fijar(principal, tenantId);
              command.run();
            } finally {
              fijar(principalPrevio, tenantPrevio);
            }
          });
    };
  }

  private static void fijar(AuthPrincipal principal, UUID tenantId) {
    if (principal != null) {
      AuthContext.set(principal);
    } else {
      AuthContext.clear();
    }
    if (tenantId != null) {
      TenantContext.setTenantId(tenantId);
    } else {
      TenantContext.clear();
    }
  }
}
