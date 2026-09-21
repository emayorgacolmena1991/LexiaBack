package com.lexia.api.modules.tenancy;

import com.lexia.api.modules.identity.TenantParameter;
import com.lexia.api.modules.identity.TenantParameterRepository;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class TenantParameterService {

  public static final String KEY_INVITE_TTL_HOURS = "invite_ttl_hours";
  public static final String KEY_MFA_REQUIRED = "mfa_required";
  public static final String KEY_CASE_CODE_PATTERN = "case_code_pattern";
  public static final String KEY_SLA_DEFAULT_HOURS = "sla_default_hours";

  private final TenantParameterRepository parameters;

  public TenantParameterService(TenantParameterRepository parameters) {
    this.parameters = parameters;
  }

  @Transactional(readOnly = true)
  public String getValue(UUID tenantId, String key, String defaultValue) {
    return parameters
        .findByTenantIdAndParamKey(tenantId, key)
        .map(TenantParameter::getParamValue)
        .orElse(defaultValue);
  }

  @Transactional
  public void setValue(UUID tenantId, String key, String value) {
    parameters
        .findByTenantIdAndParamKey(tenantId, key)
        .ifPresentOrElse(
            parameter -> {
              parameter.setParamValue(value);
              parameters.save(parameter);
            },
            () -> parameters.save(TenantParameter.of(tenantId, key, value)));
  }

  @Transactional(readOnly = true)
  public int getInviteTtlHours(UUID tenantId) {
    String raw = getValue(tenantId, KEY_INVITE_TTL_HOURS, "72");
    try {
      int hours = Integer.parseInt(raw.trim());
      return Math.max(24, Math.min(hours, 168));
    } catch (NumberFormatException ex) {
      return 72;
    }
  }

  @Transactional(readOnly = true)
  public boolean isMfaRequired(UUID tenantId) {
    return "true".equalsIgnoreCase(getValue(tenantId, KEY_MFA_REQUIRED, "false").trim());
  }

  @Transactional(readOnly = true)
  public String getCaseCodePattern(UUID tenantId) {
    return getValue(tenantId, KEY_CASE_CODE_PATTERN, "LEX-YYYY-###").trim();
  }

  @Transactional(readOnly = true)
  public int getSlaDefaultHours(UUID tenantId) {
    String raw = getValue(tenantId, KEY_SLA_DEFAULT_HOURS, "72");
    try {
      int hours = Integer.parseInt(raw.trim());
      return Math.max(1, Math.min(hours, 8760));
    } catch (NumberFormatException ex) {
      return 72;
    }
  }
}
