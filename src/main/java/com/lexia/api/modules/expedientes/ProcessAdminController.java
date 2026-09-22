package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.ChangeSetCommentRequest;
import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.ProcessPublicationHistoryView;
import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.PublishProcessConfigRequest;
import com.lexia.api.modules.expedientes.TenantConfigDtos.ProcessConfig;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/process-definitions")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessAdminController {

  private final ProcessAdminService processAdminService;
  private final ProcessConfigPublishService publishService;
  private final ProcessConfigChangeSetService changeSetService;
  private final ProcessConfigImpactService impactService;
  private final ProcessConfigPackageService packageService;

  public ProcessAdminController(
      ProcessAdminService processAdminService,
      ProcessConfigPublishService publishService,
      ProcessConfigChangeSetService changeSetService,
      ProcessConfigImpactService impactService,
      ProcessConfigPackageService packageService) {
    this.processAdminService = processAdminService;
    this.publishService = publishService;
    this.changeSetService = changeSetService;
    this.impactService = impactService;
    this.packageService = packageService;
  }

  @GetMapping("/{caseType}")
  public ProcessConfig get(@PathVariable String caseType) {
    return processAdminService.getDefinition(caseType);
  }

  @PutMapping("/{caseType}")
  public ProcessConfig update(
      @PathVariable String caseType,
      @Valid @RequestBody ProcessAdminDtos.UpdateProcessDefinitionRequest request) {
    return processAdminService.updateDefinition(caseType, request);
  }

  @PostMapping("/{caseType}/gates")
  public ProcessConfig createGate(
      @PathVariable String caseType, @Valid @RequestBody ProcessAdminDtos.CreateGateRequest request) {
    return processAdminService.createGate(caseType, request);
  }

  @PostMapping("/{caseType}/validations")
  public ProcessConfig createValidation(
      @PathVariable String caseType,
      @Valid @RequestBody ProcessAdminDtos.CreateValidationRequest request) {
    return processAdminService.createValidation(caseType, request);
  }

  @PostMapping("/{caseType}/change-set/submit")
  public ProcessConfig submitChangeSet(
      @PathVariable String caseType, @Valid @RequestBody ChangeSetCommentRequest request) {
    changeSetService.submitForReview(caseType, request);
    return processAdminService.getDefinition(caseType);
  }

  @PostMapping("/{caseType}/change-set/approve")
  public ProcessConfig approveChangeSet(
      @PathVariable String caseType, @Valid @RequestBody ChangeSetCommentRequest request) {
    changeSetService.approve(caseType, request);
    return processAdminService.getDefinition(caseType);
  }

  @PostMapping("/{caseType}/change-set/reject")
  public ProcessConfig rejectChangeSet(
      @PathVariable String caseType, @Valid @RequestBody ChangeSetCommentRequest request) {
    changeSetService.reject(caseType, request);
    return processAdminService.getDefinition(caseType);
  }

  @PostMapping("/{caseType}/publish")
  public ProcessConfig publish(
      @PathVariable String caseType, @Valid @RequestBody PublishProcessConfigRequest request) {
    return publishService.publish(caseType, request);
  }

  @GetMapping("/{caseType}/publications")
  public ProcessPublicationHistoryView publications(@PathVariable String caseType) {
    return publishService.history(caseType);
  }

  @GetMapping("/{caseType}/publications/diff")
  public ProcessConfigPublishDtos.ProcessConfigDiffView diffVersions(
      @PathVariable String caseType,
      @org.springframework.web.bind.annotation.RequestParam int fromVersion,
      @org.springframework.web.bind.annotation.RequestParam int toVersion) {
    return publishService.diffVersions(caseType, fromVersion, toVersion);
  }

  @GetMapping("/{caseType}/publications/diff/draft")
  public ProcessConfigPublishDtos.ProcessConfigDiffView diffDraft(
      @PathVariable String caseType) {
    return publishService.diffDraftAgainstActive(caseType);
  }

  @GetMapping("/{caseType}/impact/draft")
  public ProcessConfigPublishDtos.ProcessConfigImpactView impactDraft(
      @PathVariable String caseType) {
    return impactService.analyzeDraft(caseType);
  }

  @GetMapping("/{caseType}/config-package/export")
  public ProcessConfigPublishDtos.ProcessConfigExportPackage exportPackage(
      @PathVariable String caseType) {
    return packageService.export(caseType);
  }

  @PostMapping("/{caseType}/config-package/import/preview")
  public ProcessConfigPublishDtos.ProcessConfigImportPreviewView previewImport(
      @PathVariable String caseType,
      @Valid @RequestBody ProcessConfigPublishDtos.ProcessConfigImportPreviewRequest request) {
    return packageService.previewImport(caseType, request.snapshotJson());
  }
}
