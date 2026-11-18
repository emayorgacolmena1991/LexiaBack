package com.lexia.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class AsyncConfigTest {

  private final UUID tenant = UUID.randomUUID();
  private final AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), null, tenant, null);

  @AfterEach
  void limpiar() {
    AuthContext.clear();
    TenantContext.clear();
  }

  @Test
  void colaLlenaCorreEnElLlamadorSinBorrarleElContexto() throws Exception {
    ThreadPoolTaskExecutor pool =
        ReflectionTestUtils.invokeMethod(AsyncConfig.class, "newPool", "test-", 1, 1, 0);
    Executor executor = ReflectionTestUtils.invokeMethod(AsyncConfig.class, "wrapWithTenantAuth", pool);
    AuthContext.set(principal);
    TenantContext.setTenantId(tenant);

    CountDownLatch ocupado = new CountDownLatch(1);
    CountDownLatch soltar = new CountDownLatch(1);
    executor.execute(
        () -> {
          ocupado.countDown();
          try {
            soltar.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(ocupado.await(5, TimeUnit.SECONDS));

    AtomicReference<String> hilo = new AtomicReference<>();
    AtomicReference<UUID> tenantVisto = new AtomicReference<>();
    executor.execute(
        () -> {
          hilo.set(Thread.currentThread().getName());
          tenantVisto.set(TenantContext.getTenantId());
        });
    soltar.countDown();

    assertEquals(Thread.currentThread().getName(), hilo.get());
    assertEquals(tenant, tenantVisto.get());
    assertSame(principal, AuthContext.get());
    assertEquals(tenant, TenantContext.getTenantId());
    pool.shutdown();
  }

  @Test
  void hiloDelPoolNoHeredaElTenantDeLaTareaAnterior() throws Exception {
    ThreadPoolTaskExecutor pool =
        ReflectionTestUtils.invokeMethod(AsyncConfig.class, "newPool", "test-", 1, 1, 10);
    Executor executor = ReflectionTestUtils.invokeMethod(AsyncConfig.class, "wrapWithTenantAuth", pool);

    AuthContext.set(principal);
    TenantContext.setTenantId(tenant);
    CountDownLatch primera = new CountDownLatch(1);
    executor.execute(primera::countDown);
    assertTrue(primera.await(5, TimeUnit.SECONDS));

    limpiar();
    AtomicReference<UUID> visto = new AtomicReference<>(UUID.randomUUID());
    AtomicReference<AuthPrincipal> principalVisto = new AtomicReference<>(principal);
    CountDownLatch segunda = new CountDownLatch(1);
    executor.execute(
        () -> {
          visto.set(TenantContext.getTenantId());
          principalVisto.set(AuthContext.get());
          segunda.countDown();
        });
    assertTrue(segunda.await(5, TimeUnit.SECONDS));

    assertNull(visto.get());
    assertNull(principalVisto.get());
    pool.shutdown();
  }
}
