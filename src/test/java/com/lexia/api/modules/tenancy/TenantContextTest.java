package com.lexia.api.modules.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void storesAndClearsTenant() {
    UUID tenantId = UUID.fromString("b1000000-0000-7000-8000-000000000001");
    TenantContext.setTenantId(tenantId);
    assertEquals(tenantId, TenantContext.getTenantId());
    TenantContext.clear();
    assertNull(TenantContext.getTenantId());
  }

  @Test
  void resolverReturnsEmptyWhenUnset() {
    CurrentTenantResolver resolver = new CurrentTenantResolver();
    assertEquals(TenantContext.UNSET, resolver.resolveCurrentTenantIdentifier());
    UUID tenantId = UUID.fromString("b1000000-0000-7000-8000-000000000001");
    TenantContext.setTenantId(tenantId);
    assertEquals(tenantId.toString(), resolver.resolveCurrentTenantIdentifier());
  }
}
