package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantContext;
import com.lexia.api.modules.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Al arrancar, completa el catálogo de plantillas coactivas en todos los tenants activos.
 * Cubre bases nuevas y tenants creados después de V66.
 */
@Component
public class CoactivaPlantillaCatalogBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaPlantillaCatalogBootstrap.class);

  private final TenantRepository tenants;
  private final CoactivaPlantillaCatalogSeeder seeder;
  private final TransactionTemplate tx;

  public CoactivaPlantillaCatalogBootstrap(
      TenantRepository tenants,
      CoactivaPlantillaCatalogSeeder seeder,
      PlatformTransactionManager transactionManager) {
    this.tenants = tenants;
    this.seeder = seeder;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void sembrarTenantsActivos() {
    for (Tenant tenant : tenants.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
      try {
        tx.executeWithoutResult(status -> seeder.sembrar(tenant.getId()));
      } catch (Exception ex) {
        LOG.warn(
            "No se pudo sembrar plantillas coactivas para tenant {} ({}): {}",
            tenant.getCode(),
            tenant.getId(),
            ex.toString());
      } finally {
        TenantContext.clear();
      }
    }
  }
}
