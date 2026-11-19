package com.lexia.api.modules.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.identity.AuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class PlatformReportService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final AuthorizationService authorization;
  private final PlatformTenantService platformTenants;

  public PlatformReportService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      AuthorizationService authorization,
      PlatformTenantService platformTenants) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.authorization = authorization;
    this.platformTenants = platformTenants;
  }

  @Transactional(readOnly = true)
  public PlatformDtos.PlatformReport usage() {
    platformTenants.assertPlatformOperator();
    authorization.requirePermission("platform:tenant:leer");
    String json = jdbc.queryForObject("SELECT app.platform_usage_report()::text", String.class);
    try {
      return objectMapper.readValue(json, PlatformDtos.PlatformReport.class);
    } catch (Exception ex) {
      throw new IllegalStateException("No se pudo leer el reporte de plataforma", ex);
    }
  }
}