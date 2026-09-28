package com.lexia.api.modules.expedientes.tenant;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.AiGovernanceView;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.AiPolicyView;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.FeatureFlagItem;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.TenantCapabilitiesView;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.UpdateAiPolicyRequest;
import com.lexia.api.modules.expedientes.tenant.TenantGovernanceDtos.UpdateFeatureFlagRequest;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class TenantGovernanceService {

  private final TenantAiPolicyRepository aiPolicies;
  private final TenantFeatureFlagRepository featureFlags;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;

  public TenantGovernanceService(
      TenantAiPolicyRepository aiPolicies,
      TenantFeatureFlagRepository featureFlags,
      AuthorizationService authorization,
      AuditEventRepository auditEvents) {
    this.aiPolicies = aiPolicies;
    this.featureFlags = featureFlags;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public AiGovernanceView getForAdmin() {
    authorization.requirePermission("admin:tenant:leer");
    UUID tenantId = AuthContext.require().tenantId();
    return buildView(tenantId);
  }

  @Transactional
  public AiGovernanceView updatePolicy(UpdateAiPolicyRequest request) {
    authorization.requirePermission("admin:tenant:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    TenantAiPolicy policy = requirePolicy(tenantId);
    policy.setDocumentExtraction(request.documentExtraction());
    policy.setWorkspaceAssist(request.workspaceAssist());
    policy.setRoutingMode(request.routingMode().trim().toUpperCase(Locale.ROOT));
    policy.setMaxDailyDocumentJobs(request.maxDailyDocumentJobs());
    policy.setMinConfidencePercent(request.minConfidencePercent());
    policy.touch(userId);
    aiPolicies.save(policy);
    audit("admin.ai_policy.updated", tenantId, policy.getRoutingMode());
    return buildView(tenantId);
  }

  @Transactional
  public AiGovernanceView updateFeatureFlags(List<UpdateFeatureFlagRequest> updates) {
    authorization.requirePermission("admin:tenant:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    for (UpdateFeatureFlagRequest update : updates) {
      String code = update.code().trim().toUpperCase(Locale.ROOT);
      TenantFeatureFlag flag =
          featureFlags
              .findByTenantIdAndCode(tenantId, code)
              .orElseThrow(
                  () ->
                      new AuthException(
                          HttpStatus.NOT_FOUND,
                          "FEATURE_FLAG_NOT_FOUND",
                          "Feature flag no registrado: " + code));
      flag.setEnabled(Boolean.TRUE.equals(update.enabled()));
      flag.touch(userId);
      featureFlags.save(flag);
      audit("admin.feature_flag.updated", tenantId, code + "=" + flag.isEnabled());
    }
    return buildView(tenantId);
  }

  @Transactional(readOnly = true)
  public TenantCapabilitiesView capabilitiesForCurrentTenant() {
    UUID tenantId = AuthContext.require().tenantId();
    return buildCapabilities(tenantId);
  }

  @Transactional(readOnly = true)
  public boolean isFeatureEnabled(UUID tenantId, String code) {
    return featureFlags
        .findByTenantIdAndCode(tenantId, code.trim().toUpperCase(Locale.ROOT))
        .map(TenantGovernanceService::effectiveEnabled)
        .orElse(false);
  }

  @Transactional
  public void assertDocumentExtractionAllowed(UUID tenantId, int fileCount) {
    if (!isFeatureEnabled(tenantId, TenantFeatureFlagCodes.DOCUMENT_AI_EXTRACTION)) {
      throw new AuthException(
          HttpStatus.FORBIDDEN,
          "FEATURE_DISABLED",
          "La extracción documental con IA está desactivada para esta organización.");
    }
    TenantAiPolicy policy = requirePolicy(tenantId);
    if (!policy.isDocumentExtraction()) {
      throw new AuthException(
          HttpStatus.FORBIDDEN,
          "AI_POLICY_DISABLED",
          "La política de IA deshabilita la extracción documental.");
    }
    LocalDate today = LocalDate.now();
    if (policy.wouldExceedDailyQuota(fileCount, today)) {
      throw new AuthException(
          HttpStatus.TOO_MANY_REQUESTS,
          "AI_QUOTA_EXCEEDED",
          "Cuota diaria de jobs documentales alcanzada.");
    }
    policy.reserveDocumentJobs(fileCount, today);
    policy.touch(AuthContext.require().userId());
    aiPolicies.save(policy);
  }

  @Transactional(readOnly = true)
  public String documentRoutingMode(UUID tenantId) {
    return requirePolicy(tenantId).getRoutingMode();
  }

  private AiGovernanceView buildView(UUID tenantId) {
    TenantAiPolicy policy = requirePolicy(tenantId);
    List<FeatureFlagItem> flags =
        featureFlags.findByTenantIdOrderByCodeAsc(tenantId).stream()
            .map(
                row ->
                    new FeatureFlagItem(
                        row.getCode(),
                        effectiveEnabled(row),
                        row.getDescription(),
                        row.getExpiresAt(),
                        row.getUpdatedAt()))
            .toList();
    return new AiGovernanceView(toPolicyView(policy), flags);
  }

  private TenantCapabilitiesView buildCapabilities(UUID tenantId) {
    TenantAiPolicy policy = requirePolicy(tenantId);
    boolean docFlag = isFeatureEnabled(tenantId, TenantFeatureFlagCodes.DOCUMENT_AI_EXTRACTION);
    boolean workspaceFlag = isFeatureEnabled(tenantId, TenantFeatureFlagCodes.WORKSPACE_AI_ASSIST);
    List<String> enabledCodes =
        featureFlags.findByTenantIdOrderByCodeAsc(tenantId).stream()
            .filter(TenantGovernanceService::effectiveEnabled)
            .map(TenantFeatureFlag::getCode)
            .toList();
    return new TenantCapabilitiesView(
        docFlag && policy.isDocumentExtraction(),
        workspaceFlag && policy.isWorkspaceAssist(),
        policy.getRoutingMode(),
        enabledCodes);
  }

  private TenantAiPolicy requirePolicy(UUID tenantId) {
    return aiPolicies
        .findById(tenantId)
        .orElseGet(() -> aiPolicies.save(TenantAiPolicy.defaults(tenantId)));
  }

  private static AiPolicyView toPolicyView(TenantAiPolicy policy) {
    return new AiPolicyView(
        policy.isDocumentExtraction(),
        policy.isWorkspaceAssist(),
        policy.getRoutingMode(),
        policy.getMaxDailyDocumentJobs(),
        policy.getMinConfidencePercent(),
        policy.getJobsTodayCount(),
        policy.getUpdatedAt());
  }

  private static boolean effectiveEnabled(TenantFeatureFlag flag) {
    if (!flag.isEnabled()) {
      return false;
    }
    Instant expires = flag.getExpiresAt();
    return expires == null || expires.isAfter(Instant.now());
  }

  private void audit(String action, UUID tenantId, String detail) {
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "tenant_governance",
            tenantId,
            detail,
            http != null ? http.getRemoteAddr() : null,
            http != null ? http.getHeader("User-Agent") : null));
  }

  private static HttpServletRequest currentRequest() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest();
    }
    return null;
  }
}
