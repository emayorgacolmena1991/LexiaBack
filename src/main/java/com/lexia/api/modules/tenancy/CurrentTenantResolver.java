package com.lexia.api.modules.tenancy;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class CurrentTenantResolver implements CurrentTenantIdentifierResolver<String> {

  @Override
  public String resolveCurrentTenantIdentifier() {
    UUID tenantId = TenantContext.getTenantId();
    return tenantId != null ? tenantId.toString() : TenantContext.UNSET;
  }

  @Override
  public boolean validateExistingCurrentSessions() {
    return false;
  }
}
