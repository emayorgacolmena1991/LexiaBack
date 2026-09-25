package com.lexia.api.modules.expedientes.ejd;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ejd")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdDocumentRequirementAdminController {

  private final EjdDocumentRequirementAdminService documentAdminService;
  private final EjdIntegrationService ejdIntegrationService;
  private final EjdWorkflowAdminService workflowAdminService;

  public EjdDocumentRequirementAdminController(
      EjdDocumentRequirementAdminService documentAdminService,
      EjdIntegrationService ejdIntegrationService,
      EjdWorkflowAdminService workflowAdminService) {
    this.documentAdminService = documentAdminService;
    this.ejdIntegrationService = ejdIntegrationService;
    this.workflowAdminService = workflowAdminService;
  }

  @GetMapping("/document-requirements")
  public EjdDocumentAdminDtos.DocumentRequirementsView list() {
    return documentAdminService.list();
  }

  @PutMapping("/operations/{operationCode}/documents")
  public EjdDocumentAdminDtos.DocumentRequirementsView replaceForOperation(
      @PathVariable String operationCode,
      @Valid @RequestBody EjdDocumentAdminDtos.ReplaceOperationDocumentsRequest request) {
    return documentAdminService.replaceForOperation(operationCode, request);
  }

  @GetMapping("/stage-integrations")
  public EjdIntegrationDtos.StageIntegrationsAdminView listStageIntegrations() {
    return ejdIntegrationService.getStageIntegrationsForAdmin();
  }

  @PutMapping("/stages/{stageCode}/integrations")
  public EjdIntegrationDtos.StageIntegrationsAdminView replaceStageIntegrations(
      @PathVariable String stageCode,
      @Valid @RequestBody EjdIntegrationDtos.ReplaceStageIntegrationsRequest request) {
    return ejdIntegrationService.replaceStageIntegrations(stageCode, request);
  }

  @GetMapping("/stage-transitions")
  public EjdWorkflowAdminDtos.StageTransitionsAdminView listStageTransitions() {
    return workflowAdminService.getStageTransitionsForAdmin();
  }

  @PutMapping("/stage-transitions")
  public EjdWorkflowAdminDtos.StageTransitionsAdminView replaceStageTransitions(
      @Valid @RequestBody EjdWorkflowAdminDtos.ReplaceStageTransitionsRequest request) {
    return workflowAdminService.replaceStageTransitions(request);
  }

  @GetMapping("/stage-gates")
  public EjdWorkflowAdminDtos.StageGatesAdminView listStageGates() {
    return workflowAdminService.getStageGatesForAdmin();
  }

  @PutMapping("/stages/{stageCode}/gates")
  public EjdWorkflowAdminDtos.StageGatesAdminView replaceStageGates(
      @PathVariable String stageCode,
      @Valid @RequestBody EjdWorkflowAdminDtos.ReplaceStageGatesRequest request) {
    return workflowAdminService.replaceStageGates(stageCode, request);
  }
}
