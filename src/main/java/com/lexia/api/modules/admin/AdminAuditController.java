package com.lexia.api.modules.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminAuditController {

  private final AuditAdminService auditAdminService;

  public AdminAuditController(AuditAdminService auditAdminService) {
    this.auditAdminService = auditAdminService;
  }

  @GetMapping("/summary")
  public AdminDtos.AuditSummary summary() {
    return auditAdminService.summary();
  }

  @GetMapping("/events")
  public AdminDtos.PageResponse<AdminDtos.AuditEventItem> events(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String result) {
    return auditAdminService.listEvents(page, size, q, result);
  }
}
