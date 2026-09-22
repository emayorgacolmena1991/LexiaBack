package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class RuleAdminDtos {

  private RuleAdminDtos() {}

  public record RuleVersionItem(
      UUID id,
      String code,
      int version,
      String name,
      String status,
      String bodyPreview) {}

  public record RuleRegistryView(String vertical, List<RuleVersionItem> rules) {}

  public record TemplateItem(
      UUID id,
      String code,
      int version,
      String name,
      String vertical,
      String status) {}

  public record TemplateRegistryView(String vertical, List<TemplateItem> templates) {}

  public record UpdateRuleDraftBodyRequest(@NotBlank @Size(max = 16000) String body) {}
}
