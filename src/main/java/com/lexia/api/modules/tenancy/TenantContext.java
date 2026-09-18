package com.lexia.api.modules.tenancy;

import java.util.UUID;

/** Tenant del hilo actual. Se limpia al terminar el request. */
public final class TenantContext {

  public static final String UNSET = "";

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void setTenantId(UUID tenantId) {
    CURRENT.set(tenantId);
  }

  public static UUID getTenantId() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
