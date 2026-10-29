package com.lexia.api.modules.notary;

import com.lexia.api.modules.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reglas de acceso cross-tenant (opción A). Las tablas en {@code control} no tienen RLS; el servicio
 * debe comprobar que el tenant de sesión es el bufete o la notaría de la fila.
 */
@Component
public class NotaryAccessGuard {

  public void requireRemisionAccess(NotaryRemision remision) {
    UUID current = requireCurrentTenant();
    if (!current.equals(remision.getFirmTenantId()) && !current.equals(remision.getNotaryTenantId())) {
      throw new IllegalStateException("Tenant sin acceso a la remisión");
    }
  }

  public void requireFirmContext(UUID firmTenantId) {
    if (!requireCurrentTenant().equals(firmTenantId)) {
      throw new IllegalStateException("Operación reservada al tenant bufete");
    }
  }

  public void requireNotaryContext(UUID notaryTenantId) {
    if (!requireCurrentTenant().equals(notaryTenantId)) {
      throw new IllegalStateException("Operación reservada al tenant notaría");
    }
  }

  private static UUID requireCurrentTenant() {
    UUID current = TenantContext.getTenantId();
    if (current == null) {
      throw new IllegalStateException("Tenant no establecido en contexto");
    }
    return current;
  }
}
