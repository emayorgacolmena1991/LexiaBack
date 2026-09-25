package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.RuleRegistryView;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.RuleVersionItem;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.TemplateRegistryView;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.UpdateRuleDraftBodyRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/rules")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class RuleAdminController {

  private final RuleAdminService ruleAdminService;

  public RuleAdminController(RuleAdminService ruleAdminService) {
    this.ruleAdminService = ruleAdminService;
  }

  @GetMapping
  public RuleRegistryView listRules(@RequestParam(defaultValue = "EJD") String vertical) {
    return ruleAdminService.listRules(vertical);
  }

  @GetMapping("/templates")
  public TemplateRegistryView listTemplates(@RequestParam(defaultValue = "EJD") String vertical) {
    return ruleAdminService.listTemplates(vertical);
  }

  @PostMapping("/{code}/draft")
  public RuleVersionItem createDraft(@PathVariable String code) {
    return ruleAdminService.createDraftVersion(code);
  }

  @PutMapping("/versions/{ruleId}/body")
  public RuleVersionItem updateDraftBody(
      @PathVariable UUID ruleId, @Valid @RequestBody UpdateRuleDraftBodyRequest request) {
    return ruleAdminService.updateDraftBody(ruleId, request);
  }

  @PostMapping("/versions/{ruleId}/submit")
  public RuleVersionItem submit(@PathVariable UUID ruleId) {
    return ruleAdminService.submitReview(ruleId);
  }

  @PostMapping("/versions/{ruleId}/approve")
  public RuleVersionItem approve(@PathVariable UUID ruleId) {
    return ruleAdminService.approve(ruleId);
  }

  @PostMapping("/versions/{ruleId}/activate")
  public RuleVersionItem activate(@PathVariable UUID ruleId) {
    return ruleAdminService.activate(ruleId);
  }
}
