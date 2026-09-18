package com.lexia.api.modules.tenancy;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.Session;

public class TenantBinder {

  private final EntityManager entityManager;

  public TenantBinder(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public void bind(UUID tenantId) {
    TenantContext.setTenantId(tenantId);
    if (tenantId == null || entityManager == null) {
      return;
    }
    entityManager
        .unwrap(Session.class)
        .doWork(connection -> RlsConnectionProvider.applyTenant(connection, tenantId.toString()));
  }
}
