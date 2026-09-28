package com.lexia.api.modules.expedientes.reglas;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.RuleRegistryView;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.RuleVersionItem;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.TemplateItem;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.TemplateRegistryView;
import com.lexia.api.modules.expedientes.reglas.RuleAdminDtos.UpdateRuleDraftBodyRequest;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.proceso.ChangeSetDomain;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigChangeService;
import com.lexia.api.modules.expedientes.proceso.TemplateDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class RuleAdminService {

  private final RuleDefRepository rules;
  private final TemplateDefRepository templates;
  private final AuthorizationService authorization;
  private final RuleValidationRelinkService relinkService;
  private final ProcessConfigChangeService configChanges;
  private final AuditEventRepository auditEvents;

  public RuleAdminService(
      RuleDefRepository rules,
      TemplateDefRepository templates,
      AuthorizationService authorization,
      RuleValidationRelinkService relinkService,
      ProcessConfigChangeService configChanges,
      AuditEventRepository auditEvents) {
    this.rules = rules;
    this.templates = templates;
    this.authorization = authorization;
    this.relinkService = relinkService;
    this.configChanges = configChanges;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public RuleRegistryView listRules(String verticalParam) {
    authorization.requirePermission("admin:reglas:leer");
    String vertical = normalizeVertical(verticalParam);
    UUID tenantId = AuthContext.require().tenantId();
    String prefix = vertical + ".";
    List<RuleVersionItem> items =
        rules.findByTenantIdOrderByCodeAscVersionDesc(tenantId).stream()
            .filter(rule -> rule.getCode() != null && rule.getCode().startsWith(prefix))
            .map(this::toRuleItem)
            .toList();
    return new RuleRegistryView(vertical, items);
  }

  @Transactional(readOnly = true)
  public TemplateRegistryView listTemplates(String verticalParam) {
    authorization.requirePermission("admin:reglas:leer");
    String vertical = normalizeVertical(verticalParam);
    UUID tenantId = AuthContext.require().tenantId();
    List<TemplateItem> items =
        templates.findByTenantIdAndVerticalOrderByCodeAscVersionDesc(tenantId, vertical).stream()
            .map(
                row ->
                    new TemplateItem(
                        row.getId(),
                        row.getCode(),
                        row.getVersion(),
                        row.getName(),
                        row.getVertical(),
                        row.getStatus()))
            .toList();
    return new TemplateRegistryView(vertical, items);
  }

  @Transactional
  public RuleVersionItem createDraftVersion(String codeParam) {
    authorization.requirePermission("admin:reglas:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    String code = codeParam.trim().toUpperCase(Locale.ROOT);
    RuleDef active =
        rules
            .findFirstByTenantIdAndCodeAndStatusOrderByVersionDesc(tenantId, code, "ACTIVE")
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "No hay regla activa " + code));
    int nextVersion =
        rules.findByTenantIdAndCodeOrderByVersionDesc(tenantId, code).stream()
            .mapToInt(RuleDef::getVersion)
            .max()
            .orElse(active.getVersion())
            + 1;
    RuleDef draft = newDraftFrom(active, nextVersion);
    setField(draft, "id", UUID.randomUUID());
    setField(draft, "tenantId", tenantId);
    setField(draft, "status", "DRAFT");
    rules.save(draft);
    return toRuleItem(draft);
  }

  @Transactional
  public RuleVersionItem updateDraftBody(UUID ruleId, UpdateRuleDraftBodyRequest request) {
    authorization.requirePermission("admin:reglas:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    RuleDef rule = requireRule(tenantId, ruleId);
    try {
      rule.updateDraftBody(request.body().trim());
    } catch (IllegalStateException ex) {
      throw new AuthException(HttpStatus.CONFLICT, "RULE_NOT_EDITABLE", ex.getMessage());
    }
    rules.save(rule);
    return toRuleItem(rule);
  }

  @Transactional
  public RuleVersionItem submitReview(UUID ruleId) {
    authorization.requirePermission("admin:reglas:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    RuleDef rule = requireRule(tenantId, ruleId);
    try {
      rule.submitForReview();
    } catch (IllegalStateException ex) {
      throw new AuthException(HttpStatus.CONFLICT, "RULE_INVALID_STATE", ex.getMessage());
    }
    rules.save(rule);
    return toRuleItem(rule);
  }

  @Transactional
  public RuleVersionItem approve(UUID ruleId) {
    authorization.requirePermission("admin:reglas:aprobar");
    UUID tenantId = AuthContext.require().tenantId();
    RuleDef rule = requireRule(tenantId, ruleId);
    try {
      rule.approve();
    } catch (IllegalStateException ex) {
      throw new AuthException(HttpStatus.CONFLICT, "RULE_INVALID_STATE", ex.getMessage());
    }
    rules.save(rule);
    return toRuleItem(rule);
  }

  @Transactional
  public RuleVersionItem activate(UUID ruleId) {
    authorization.requirePermission("admin:reglas:aprobar");
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    RuleDef rule = requireRule(tenantId, ruleId);
    UUID previousActiveId =
        rules
            .findFirstByTenantIdAndCodeAndStatusOrderByVersionDesc(
                tenantId, rule.getCode(), "ACTIVE")
            .map(RuleDef::getId)
            .filter(id -> !id.equals(rule.getId()))
            .orElse(null);
    if (previousActiveId != null) {
      rules
          .findByIdAndTenantId(previousActiveId, tenantId)
          .ifPresent(
              current -> {
                current.retire();
                rules.save(current);
              });
    }
    try {
      rule.activate();
    } catch (IllegalStateException ex) {
      throw new AuthException(HttpStatus.CONFLICT, "RULE_INVALID_STATE", ex.getMessage());
    }
    rules.save(rule);
    int relinked = relinkService.relinkOnActivation(tenantId, rule, previousActiveId);
    RuleValidationRelinkService.parseRuleBinding(rule.getCode())
        .ifPresent(
            binding ->
                configChanges.markDraftByCaseType(
                    tenantId,
                    binding.vertical(),
                    ChangeSetDomain.RULE,
                    "Regla activada: " + rule.getCode(),
                    rule.getCode()));
    auditRuleActivated(tenantId, userId, rule, relinked);
    return toRuleItem(rule);
  }

  private RuleDef requireRule(UUID tenantId, UUID ruleId) {
    return rules
        .findByIdAndTenantId(ruleId, tenantId)
        .orElseThrow(
            () ->
                new AuthException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Regla no encontrada."));
  }

  private RuleVersionItem toRuleItem(RuleDef rule) {
    String body = rule.getBody();
    String preview =
        body == null ? "" : body.length() > 120 ? body.substring(0, 120) + "…" : body;
    return new RuleVersionItem(
        rule.getId(), rule.getCode(), rule.getVersion(), rule.getName(), rule.getStatus(), preview);
  }

  private static String normalizeVertical(String verticalParam) {
    String vertical = verticalParam.trim().toUpperCase(Locale.ROOT);
    if (!"EJD".equals(vertical) && !"ECD".equals(vertical)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "INVALID_VERTICAL", "Vertical debe ser EJD o ECD.");
    }
    return vertical;
  }

  private static RuleDef newDraftFrom(RuleDef active, int nextVersion) {
    RuleDef draft = new RuleDef();
    setField(draft, "code", active.getCode());
    setField(draft, "version", nextVersion);
    setField(draft, "name", active.getName());
    setField(draft, "body", active.getBody());
    return draft;
  }

  private void auditRuleActivated(UUID tenantId, UUID userId, RuleDef rule, int relinked) {
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            "admin.rule.activated",
            "rule_def",
            rule.getId(),
            "OK " + rule.getCode() + " v" + rule.getVersion() + " relink=" + relinked,
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

  private static void setField(Object target, String field, Object value) {
    try {
      var f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
