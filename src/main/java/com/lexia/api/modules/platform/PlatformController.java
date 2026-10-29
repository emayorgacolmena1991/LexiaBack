package com.lexia.api.modules.platform;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class PlatformController {

  private final PlatformTenantService platformTenants;

  public PlatformController(PlatformTenantService platformTenants) {
    this.platformTenants = platformTenants;
  }

  @GetMapping
  public List<PlatformDtos.TenantSummary> list() {
    return platformTenants.listTenants();
  }

  @PostMapping
  public PlatformDtos.ProvisionTenantResponse provision(
      @Valid @RequestBody PlatformDtos.ProvisionTenantRequest request) {
    return platformTenants.provision(request);
  }

  @GetMapping("/{tenantId}")
  public PlatformDtos.TenantDetail get(@PathVariable UUID tenantId) {
    return platformTenants.getTenant(tenantId);
  }

  @PatchMapping("/{tenantId}")
  public PlatformDtos.TenantDetail update(
      @PathVariable UUID tenantId, @Valid @RequestBody PlatformDtos.UpdateTenantRequest request) {
    return platformTenants.updateTenant(tenantId, request);
  }

  @PatchMapping("/{tenantId}/status")
  public PlatformDtos.TenantDetail updateStatus(
      @PathVariable UUID tenantId,
      @Valid @RequestBody PlatformDtos.UpdateTenantStatusRequest request) {
    return platformTenants.updateTenantStatus(tenantId, request.status());
  }

  @PostMapping("/{tenantId}/admin-invitations")
  public PlatformDtos.InviteAdminResponse inviteAdmin(
      @PathVariable UUID tenantId, @Valid @RequestBody PlatformDtos.InviteAdminRequest request) {
    return platformTenants.inviteAdmin(tenantId, request);
  }
}
