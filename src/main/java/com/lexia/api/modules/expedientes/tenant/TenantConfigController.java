package com.lexia.api.modules.expedientes.tenant;

import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.TenantVerticalConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class TenantConfigController {

  private final TenantConfigService tenantConfigService;
  private final TenantGovernanceService governanceService;

  public TenantConfigController(
      TenantConfigService tenantConfigService, TenantGovernanceService governanceService) {
    this.tenantConfigService = tenantConfigService;
    this.governanceService = governanceService;
  }

  @GetMapping("/config")
  public TenantVerticalConfig config(@RequestParam(defaultValue = "EJD") String caseType) {
    return tenantConfigService.getConfig(caseType);
  }

  @GetMapping("/capabilities")
  public TenantGovernanceDtos.TenantCapabilitiesView capabilities() {
    return governanceService.capabilitiesForCurrentTenant();
  }
}
