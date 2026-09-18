package com.lexia.api.modules.tenancy;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(DataSource.class)
public class TenancyConfig {

  @Bean
  TenantBinder tenantBinder(EntityManager entityManager) {
    return new TenantBinder(entityManager);
  }

  @Bean
  HibernatePropertiesCustomizer tenantHibernateCustomizer(
      CurrentTenantResolver resolver, RlsConnectionProvider connectionProvider) {
    return properties -> {
      properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
      properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
    };
  }
}
