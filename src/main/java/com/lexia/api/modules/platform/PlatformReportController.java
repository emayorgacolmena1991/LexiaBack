package com.lexia.api.modules.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/reports")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class PlatformReportController {

  private final PlatformReportService reports;

  public PlatformReportController(PlatformReportService reports) {
    this.reports = reports;
  }

  @GetMapping("/usage")
  public PlatformDtos.PlatformReport usage() {
    return reports.usage();
  }
}