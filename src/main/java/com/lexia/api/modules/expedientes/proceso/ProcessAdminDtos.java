package com.lexia.api.modules.expedientes.proceso;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ProcessAdminDtos {

  private ProcessAdminDtos() {}

  public record UpdateStageRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 160) String label,
      @NotBlank @Size(max = 64) String shortLabel,
      @Min(1) @Max(8760) Integer slaHours,
      @Size(max = 16) String colorKey) {}

  public record UpdateGateRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 500) String question,
      @Size(max = 16) String responseType,
      @Size(max = 16) String continueCriterion,
      Boolean mandatory,
      @Size(max = 500) String messageOk,
      @Size(max = 500) String messageFail,
      Boolean active) {}

  public record UpdateValidationRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String label,
      @Size(max = 300) String description,
      Boolean active) {}

  public record UpdateProcessDefinitionRequest(
      List<@Valid UpdateStageRequest> stages,
      List<@Valid UpdateGateRequest> gates,
      List<@Valid UpdateValidationRequest> validations) {}

  public record CreateGateRequest(@NotBlank @Size(max = 500) String question) {}

  public record CreateValidationRequest(@NotBlank @Size(max = 200) String label) {}
}
