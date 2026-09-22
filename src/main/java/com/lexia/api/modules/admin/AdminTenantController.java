package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenant")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminTenantController {

  private final TenantAdminService tenantAdminService;

  public AdminTenantController(TenantAdminService tenantAdminService) {
    this.tenantAdminService = tenantAdminService;
  }

  @GetMapping
  public AdminDtos.TenantDetail tenant() {
    return tenantAdminService.getCurrentTenant();
  }

  @PatchMapping
  public AdminDtos.TenantDetail update(@Valid @RequestBody AdminDtos.UpdateTenantRequest request) {
    return tenantAdminService.updateCurrentTenant(request);
  }

  @GetMapping("/security")
  public AdminDtos.TenantSecuritySettings security() {
    return tenantAdminService.getSecuritySettings();
  }

  @PatchMapping("/security")
  public AdminDtos.TenantSecuritySettings updateSecurity(
      @Valid @RequestBody AdminDtos.UpdateTenantSecurityRequest request) {
    return tenantAdminService.updateSecuritySettings(request);
  }
}
