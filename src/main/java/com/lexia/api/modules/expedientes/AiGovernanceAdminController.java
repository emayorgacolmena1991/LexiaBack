package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.TenantGovernanceDtos.AiGovernanceView;
import com.lexia.api.modules.expedientes.TenantGovernanceDtos.UpdateAiPolicyRequest;
import com.lexia.api.modules.expedientes.TenantGovernanceDtos.UpdateFeatureFlagsRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai-governance")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AiGovernanceAdminController {

  private final TenantGovernanceService governance;

  public AiGovernanceAdminController(TenantGovernanceService governance) {
    this.governance = governance;
  }

  @GetMapping
  public AiGovernanceView get() {
    return governance.getForAdmin();
  }

  @PutMapping("/policy")
  public AiGovernanceView updatePolicy(@Valid @RequestBody UpdateAiPolicyRequest request) {
    return governance.updatePolicy(request);
  }

  @PutMapping("/feature-flags")
  public AiGovernanceView updateFlags(@Valid @RequestBody UpdateFeatureFlagsRequest request) {
    return governance.updateFeatureFlags(request.flags());
  }
}
