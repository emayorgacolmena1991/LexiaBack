package com.lexia.api.modules.expedientes.metricas;

import java.time.Instant;
import java.util.List;

public final class CaseCycleMetricsDtos {

  private CaseCycleMetricsDtos() {}

  public static final String OUTCOME_NOTARY_APPROVED = "NOTARY_APPROVED";
  public static final String OUTCOME_COACTIVE_ARCHIVED = "COACTIVE_ARCHIVED";

  public record StageIntervalItem(
      String code, String label, Instant startedAt, Instant completedAt, Long durationSeconds) {}

  public record CaseCycleMetrics(
      String caseId,
      String caseCode,
      String vertical,
      Instant startedAt,
      Instant completedAt,
      String outcome,
      String outcomeLabel,
      long durationSeconds,
      boolean open,
      List<StageIntervalItem> stages) {}

  public record CaseCycleReportRow(
      String caseId,
      String caseCode,
      String vertical,
      String outcome,
      String outcomeLabel,
      long durationSeconds,
      String completedAt) {}

  public record CaseCycleReportPage(
      List<CaseCycleReportRow> content,
      long totalElements,
      long avgDurationSeconds,
      long medianDurationSeconds) {}
}