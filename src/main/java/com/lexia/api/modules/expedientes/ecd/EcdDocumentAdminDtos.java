package com.lexia.api.modules.expedientes.ecd;

import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class EcdDocumentAdminDtos {

  private EcdDocumentAdminDtos() {}

  public record DocumentRequirementsView(
      List<CatalogItemRef> documentTypes, List<CatalogItemRef> requiredDocuments) {}

  public record ReplaceDocumentsRequest(
      @NotNull List<@NotNull @Size(max = 64) String> documentTypeCodes) {}
}
