package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.EcdDocumentAdminDtos.DocumentRequirementsView;
import com.lexia.api.modules.expedientes.EcdDocumentAdminDtos.ReplaceDocumentsRequest;
import com.lexia.api.modules.expedientes.EjdIntegrationDtos.ReplaceStageIntegrationsRequest;
import com.lexia.api.modules.expedientes.EjdIntegrationDtos.StageIntegrationsAdminView;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageGatesRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageTransitionsRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageGatesAdminView;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageTransitionsAdminView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ecd")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EcdWorkflowAdminController {

  private final EjdWorkflowAdminService workflowAdminService;
  private final EcdDocumentRequirementAdminService documentAdminService;
  private final EjdIntegrationService integrationService;

  public EcdWorkflowAdminController(
      EjdWorkflowAdminService workflowAdminService,
      EcdDocumentRequirementAdminService documentAdminService,
      EjdIntegrationService integrationService) {
    this.workflowAdminService = workflowAdminService;
    this.documentAdminService = documentAdminService;
    this.integrationService = integrationService;
  }

  @GetMapping("/document-requirements")
  public DocumentRequirementsView listDocumentRequirements() {
    return documentAdminService.list();
  }

  @PutMapping("/document-requirements")
  public DocumentRequirementsView replaceDocumentRequirements(
      @Valid @RequestBody ReplaceDocumentsRequest request) {
    return documentAdminService.replace(request);
  }

  @GetMapping("/stage-integrations")
  public StageIntegrationsAdminView listStageIntegrations() {
    return integrationService.getStageIntegrationsForAdmin("ECD");
  }

  @PutMapping("/stages/{stageCode}/integrations")
  public StageIntegrationsAdminView replaceStageIntegrations(
      @PathVariable String stageCode, @Valid @RequestBody ReplaceStageIntegrationsRequest request) {
    return integrationService.replaceStageIntegrations("ECD", stageCode, request);
  }

  @GetMapping("/stage-transitions")
  public StageTransitionsAdminView listStageTransitions() {
    return workflowAdminService.getStageTransitionsForAdmin("ECD");
  }

  @PutMapping("/stage-transitions")
  public StageTransitionsAdminView replaceStageTransitions(
      @Valid @RequestBody ReplaceStageTransitionsRequest request) {
    return workflowAdminService.replaceStageTransitions("ECD", request);
  }

  @GetMapping("/stage-gates")
  public StageGatesAdminView listStageGates() {
    return workflowAdminService.getStageGatesForAdmin("ECD");
  }

  @PutMapping("/stages/{stageCode}/gates")
  public StageGatesAdminView replaceStageGates(
      @PathVariable String stageCode, @Valid @RequestBody ReplaceStageGatesRequest request) {
    return workflowAdminService.replaceStageGates("ECD", stageCode, request);
  }
}
