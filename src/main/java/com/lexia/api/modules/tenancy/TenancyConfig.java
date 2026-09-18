package com.lexia.api.modules.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class TenancyConfig {

  @Bean
  HibernatePropertiesCustomizer tenantHibernateCustomizer(
      CurrentTenantResolver resolver, RlsConnectionProvider connectionProvider) {
    return properties -> {
      properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
      properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
    };
  }
}
