package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.GateRef;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class EjdWorkflowAdminDtos {

  private EjdWorkflowAdminDtos() {}

  public record StageTransitionPair(String fromStageCode, String toStageCode) {}

  public record StageTransitionsAdminView(
      List<CatalogItemRef> stages, List<StageTransitionPair> transitions) {}

  public record ReplaceStageTransitionsRequest(
      @NotNull List<@NotNull StageTransitionPair> transitions) {}

  public record StageGateLinks(String stageCode, String stageLabel, List<GateRef> gates) {}

  public record StageGatesAdminView(List<CatalogItemRef> stages, List<GateRef> gates, List<StageGateLinks> links) {}

  public record ReplaceStageGatesRequest(@NotNull List<@NotNull @Size(max = 32) String> gateCodes) {}
}
