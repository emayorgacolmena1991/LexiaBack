package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** S1: el sessionId lo manda el cliente; la caché no debe cruzar tenants. */
class OcrSessionCacheTenantTest {

  private final OcrSessionCacheService cache = new OcrSessionCacheService(2);
  private final String sesion = UUID.randomUUID().toString();

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  @Test
  void otroTenantNoLeeElConsolidadoNiLosResultados() {
    como(UUID.randomUUID());
    cache.putConsolidated(sesion, "texto OCR confidencial");
    cache.putResult(
        sesion,
        new OcrSessionCacheService.OcrFileResult("f1", "f1.pdf", "CEDULA", true, null, 0.9, "texto"));

    como(UUID.randomUUID());

    assertNull(cache.getConsolidated(sesion));
    assertTrue(cache.listResults(sesion).isEmpty());
  }

  @Test
  void elMismoTenantLeeLoSuyo() {
    UUID tenant = UUID.randomUUID();
    como(tenant);
    cache.putConsolidated(sesion, "propio");

    como(tenant);

    assertEquals("propio", cache.getConsolidated(sesion));
  }

  private static void como(UUID tenant) {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), null, tenant, null));
  }
}
