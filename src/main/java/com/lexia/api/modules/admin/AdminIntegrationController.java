package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminIntegrationController {

  private final IntegrationAdminService integrationAdminService;

  public AdminIntegrationController(IntegrationAdminService integrationAdminService) {
    this.integrationAdminService = integrationAdminService;
  }

  @GetMapping
  public AdminDtos.IntegrationsOverview overview() {
    return integrationAdminService.getOverview();
  }

  @PatchMapping("/{integrationId}")
  public AdminDtos.IntegrationItem updateEnabled(
      @PathVariable UUID integrationId, @Valid @RequestBody AdminDtos.UpdateIntegrationRequest request) {
    return integrationAdminService.updateEnabled(integrationId, Boolean.TRUE.equals(request.enabled()));
  }
}
