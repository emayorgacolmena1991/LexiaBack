package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class EjdIntegrationDtos {

  private EjdIntegrationDtos() {}

  public record IntegrationConnectorRef(
      String code, String name, boolean enabled, String lastCallStatus) {}

  public record StageIntegrationLinks(
      String stageCode, String stageLabel, List<IntegrationConnectorRef> connectors) {}

  public record StageIntegrationsAdminView(
      List<CatalogItemRef> stages,
      List<IntegrationConnectorRef> availableConnectors,
      List<StageIntegrationLinks> links) {}

  public record ReplaceStageIntegrationsRequest(
      @NotNull List<@NotNull @Size(max = 64) String> integrationCodes) {}

  public record StageConnectorItem(
      String code, String name, boolean enabled, String lastCallStatus) {}
}
