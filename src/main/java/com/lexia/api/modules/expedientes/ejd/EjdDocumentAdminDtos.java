package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.OperationDocuments;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class EjdDocumentAdminDtos {

  private EjdDocumentAdminDtos() {}

  public record DocumentRequirementsView(
      List<CatalogItemRef> operationTypes,
      List<CatalogItemRef> documentTypes,
      List<OperationDocuments> requirements) {}

  public record ReplaceOperationDocumentsRequest(
      @NotNull List<@NotNull @Size(max = 64) String> documentTypeCodes) {}
}
