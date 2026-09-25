package com.lexia.api.modules.expedientes.escrituracion;

import java.util.List;

public final class EscrituracionDtos {

  private EscrituracionDtos() {}

  public record ProductoItem(String code, String label, int sortOrder, int requisitosCount) {}

  public record DocumentoRequisitoItem(
      String documentTypeCode,
      String description,
      boolean mandatory,
      int maxValidityDays,
      String canton,
      int sortOrder) {}

  public record PlantillaItem(
      String templateKind, String label, boolean companySuppliesCv, String storageKey) {}

  public record ProductoDetalle(
      String code,
      String label,
      List<DocumentoRequisitoItem> requisitos,
      List<PlantillaItem> plantillas) {}

  public record ConfigurarProductoRequest(
      String productCode, String canton, String ingestionMode, String operationTypeCode) {}

  public record EstudioTituloRequest(String status, String summary, List<String> observaciones) {}

  public record EstudioTituloResponse(
      java.util.UUID studyId, String status, String summary, List<String> openObservations) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record CrearMinutaRequest(String templateKind, String sessionId) {}

  public record MinutaItem(
      java.util.UUID id,
      String templateKind,
      String productCode,
      String status,
      boolean downloadable) {}

  public record WritingSnapshot(
      java.util.UUID writingFileId,
      String productCode,
      String canton,
      String ingestionMode,
      EstudioTituloResponse estudio,
      List<MinutaItem> minutas) {}
}
