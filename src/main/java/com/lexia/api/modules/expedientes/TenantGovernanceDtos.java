package com.lexia.api.modules.expedientes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantGovernanceDtos {

  private TenantGovernanceDtos() {}

  public record FeatureFlagItem(
      String code, boolean enabled, String description, Instant expiresAt, Instant updatedAt) {}

  public record AiPolicyView(
      boolean documentExtraction,
      boolean workspaceAssist,
      String routingMode,
      int maxDailyDocumentJobs,
      int minConfidencePercent,
      int jobsTodayCount,
      Instant updatedAt) {}

  public record AiGovernanceView(AiPolicyView policy, List<FeatureFlagItem> featureFlags) {}

  public record UpdateAiPolicyRequest(
      boolean documentExtraction,
      boolean workspaceAssist,
      @NotBlank @Pattern(regexp = "DEMO|GEMINI") String routingMode,
      @Min(1) @Max(100000) int maxDailyDocumentJobs,
      @Min(0) @Max(100) int minConfidencePercent) {}

  public record UpdateFeatureFlagRequest(
      @NotBlank @Size(max = 64) String code, @NotNull Boolean enabled) {}

  public record UpdateFeatureFlagsRequest(@Valid @NotNull List<UpdateFeatureFlagRequest> flags) {}

  public record TenantCapabilitiesView(
      boolean documentAiExtraction,
      boolean workspaceAiAssist,
      String documentRoutingMode,
      List<String> enabledFeatureFlags) {}
}
