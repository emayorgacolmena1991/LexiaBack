package com.lexia.api.modules.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** S10: con auth activa el tenant no sale del header que manda el cliente. */
class TenantHeaderFilterTest {

  private final UUID tenant = UUID.randomUUID();

  @Test
  void conAuthActivaSeIgnoraElHeader() throws Exception {
    assertNull(tenantVisto(new TenantHeaderFilter(true)));
  }

  @Test
  void sinAuthSeUsaElHeader() throws Exception {
    assertEquals(tenant, tenantVisto(new TenantHeaderFilter(false)));
  }

  @Test
  void alTerminarLimpiaElTenant() throws Exception {
    tenantVisto(new TenantHeaderFilter(false));
    assertNull(TenantContext.getTenantId());
  }

  private UUID tenantVisto(TenantHeaderFilter filtro) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/escrituracion/ingesta-masiva/x/stream");
    req.addHeader("X-Tenant-Id", tenant.toString());
    AtomicReference<UUID> visto = new AtomicReference<>();
    filtro.doFilter(req, new MockHttpServletResponse(), (r, s) -> visto.set(TenantContext.getTenantId()));
    return visto.get();
  }
}
