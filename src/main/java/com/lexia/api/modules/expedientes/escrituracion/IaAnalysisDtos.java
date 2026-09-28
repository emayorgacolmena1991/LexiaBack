package com.lexia.api.modules.expedientes.escrituracion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IaAnalysisDtos {

  private IaAnalysisDtos() {}

  public record AnalysisRequestDTO(Boolean forceReanalysis, String sessionId) {}

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
