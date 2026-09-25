package com.lexia.api.modules.expedientes.tenant;

import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessPublicationState;
import java.util.List;
import java.util.UUID;

public final class TenantConfigDtos {

  private TenantConfigDtos() {}

  public record CatalogItemRef(String code, String label) {}

  public record StageRef(
      UUID id,
      String code,
      String label,
      String shortLabel,
      int sortOrder,
      Integer slaHours,
      String colorKey) {}

  public record OperationDocuments(
      String operationCode, String operationLabel, List<CatalogItemRef> requiredDocuments) {}

  public record GateRef(
      UUID id,
      String code,
      String question,
      int sortOrder,
      String responseType,
      String continueCriterion,
      boolean mandatory,
      String messageOk,
      String messageFail,
      boolean active) {}

  public record ValidationRef(
      UUID id,
      String code,
      String label,
      int sortOrder,
      String ruleCode,
      String ruleStatus,
      String description,
      boolean active) {}

  public record ProcessConfig(
      UUID id,
      String code,
      String name,
      String caseType,
      List<StageRef> stages,
      List<GateRef> gates,
      List<ValidationRef> validations,
      ProcessPublicationState publication) {}

  public record IntegrationChannelRef(
      String code, String name, boolean enabled, String lastCallStatus) {}

  public record TenantVerticalConfig(
      String vertical,
      String caseType,
      ProcessConfig process,
      List<CatalogItemRef> operationTypes,
      List<CatalogItemRef> documentTypes,
      List<OperationDocuments> documentRequirements,
      List<IntegrationChannelRef> integrationChannels,
      String caseCodePattern,
      int slaDefaultHours) {}
}
