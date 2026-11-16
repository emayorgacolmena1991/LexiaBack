package com.lexia.api.modules.expedientes.escrituracion.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IaAnalysisDtos {

  private IaAnalysisDtos() {}

  /**
   * @param sessionId solo cuenta en un borrador: en un expediente persistido el OCR se lee por su id.
   * @param documentosReemplazados ids de documentos re-subidos desde el último análisis (opcional).
   */
  public record AnalysisRequestDTO(
      Boolean forceReanalysis, String sessionId, List<String> documentosReemplazados) {

    @com.fasterxml.jackson.annotation.JsonCreator
    public AnalysisRequestDTO {
      documentosReemplazados =
          documentosReemplazados == null
              ? List.of()
              : documentosReemplazados.stream().filter(java.util.Objects::nonNull).toList();
    }

    public AnalysisRequestDTO(Boolean forceReanalysis, String sessionId) {
      this(forceReanalysis, sessionId, List.of());
    }
  }

  public record ObservationItem(String code, String severity, String message) {}

  public record AnalysisResultDTO(
      UUID expedienteId,
      String productCode,
      String promptKeyUsed,
      String status,
      String summary,
      List<ObservationItem> observations,
      Map<String, String> extractedData) {}
}
