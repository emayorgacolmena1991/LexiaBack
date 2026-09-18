package com.lexia.api.modules.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(DataSource.class)
public class TenantBinder {

  @PersistenceContext private EntityManager entityManager;

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
