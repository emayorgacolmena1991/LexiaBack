package com.lexia.api.config;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@EnableAsync
@EnableConfigurationProperties(BrevoProperties.class)
public class AsyncConfig {

  static {
    SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
  }

  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    return wrapWithTenantAuth(newPool("lexia-async-", 4, 16, 100));
  }

  /** Pool paralelo para OCR Azure (batch documentos). */
  @Bean(name = "ocrExecutor")
  Executor ocrExecutor() {
    return wrapWithTenantAuth(newPool("AzureOCR-", 10, 20, 100));
  }

  private static ThreadPoolTaskExecutor newPool(
      String prefix, int core, int max, int queueCapacity) {
    ThreadPoolTaskExecutor delegate = new ThreadPoolTaskExecutor();
    delegate.setThreadNamePrefix(prefix);
    delegate.setCorePoolSize(core);
    delegate.setMaxPoolSize(max);
    delegate.setQueueCapacity(queueCapacity);
    delegate.initialize();
    return delegate;
  }

  private static Executor wrapWithTenantAuth(ThreadPoolTaskExecutor delegate) {
    Executor secured = new DelegatingSecurityContextExecutor(delegate);
    return command -> {
      AuthPrincipal principal = AuthContext.get();
      UUID tenantId = TenantContext.getTenantId();
      secured.execute(
          () -> {
            try {
              if (principal != null) {
                AuthContext.set(principal);
              }
              if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
              }
              command.run();
            } finally {
              AuthContext.clear();
              TenantContext.clear();
            }
          });
    };
  }
}
