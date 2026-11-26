package com.lexia.api.modules.expedientes.metricas;

import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.CaseCycleMetrics;
import com.lexia.api.modules.expedientes.metricas.CaseCycleMetricsDtos.CaseCycleReportPage;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/expedientes")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseCycleMetricsController {

  private final CaseCycleMetricsService metrics;

  public CaseCycleMetricsController(CaseCycleMetricsService metrics) {
    this.metrics = metrics;
  }

  @GetMapping("/{id}/metricas-ciclo")
  public CaseCycleMetrics metricasCiclo(@PathVariable UUID id) {
    return metrics.metricsForCase(id);
  }

  @GetMapping("/metricas-ciclos")
  public CaseCycleReportPage metricasCiclos(@RequestParam(defaultValue = "50") int limit) {
    return metrics.report(limit);
  }
}