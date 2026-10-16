package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OrquestacionMasivaTest {

  @Test
  void consolidarCuandoNoQuedaOtroJob() {
    assertTrue(IngestaMasivaEscrituracionService.listoParaConsolidar(1, 0));
    assertFalse(IngestaMasivaEscrituracionService.listoParaConsolidar(2, 0));
    assertFalse(IngestaMasivaEscrituracionService.listoParaConsolidar(1, 1));
  }

  @Test
  void elHiloDeOcrRecibeElTenantYNoLoDejaPegado() throws Exception {
    UUID tenant = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), null, tenant, null));
    TenantContext.setTenantId(tenant);
    try {
      AtomicReference<UUID> visto = new AtomicReference<>();
      Thread hilo =
          new Thread(
              HiloConTenant.copiar(
                  () -> visto.set(AuthContext.get() == null ? null : AuthContext.get().tenantId())));
      hilo.start();
      hilo.join();
      assertEquals(tenant, visto.get());

      AtomicReference<UUID> limpio = new AtomicReference<>(tenant);
      Thread otro =
          new Thread(
              () -> limpio.set(AuthContext.get() == null ? null : AuthContext.get().tenantId()));
      otro.start();
      otro.join();
      assertNull(limpio.get());
      assertEquals(tenant, AuthContext.get().tenantId());
    } finally {
      AuthContext.clear();
      TenantContext.clear();
    }
  }
}
