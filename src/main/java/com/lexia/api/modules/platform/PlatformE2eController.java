package com.lexia.api.modules.platform;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/e2e")
@ConditionalOnProperty(name = "lexia.e2e.enabled", havingValue = "true")
public class PlatformE2eController {

  private final PlatformTenantService platformTenants;

  public PlatformE2eController(PlatformTenantService platformTenants) {
    this.platformTenants = platformTenants;
  }

  @PostMapping("/tenants")
  @ResponseStatus(HttpStatus.CREATED)
  public PlatformDtos.E2eProvisionTenantResponse provision(
      @Valid @RequestBody PlatformDtos.ProvisionTenantRequest request) {
    return platformTenants.provisionForE2e(request);
  }

  @PostMapping("/tenants/{tenantId}/admin-invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public PlatformDtos.E2eInviteAdminResponse inviteAdmin(
      @PathVariable UUID tenantId, @Valid @RequestBody PlatformDtos.InviteAdminRequest request) {
    return platformTenants.inviteAdminForE2e(tenantId, request);
  }
}
