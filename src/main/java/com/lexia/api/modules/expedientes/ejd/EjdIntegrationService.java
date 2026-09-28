package com.lexia.api.modules.expedientes.ejd;

import com.lexia.api.modules.admin.Integration;
import com.lexia.api.modules.admin.IntegrationCall;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationDtos.IntegrationConnectorRef;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationDtos.StageConnectorItem;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationDtos.StageIntegrationLinks;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationDtos.StageIntegrationsAdminView;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.lexia.api.modules.expedientes.proceso.ChangeSetDomain;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigChangeService;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinition;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinitionRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdIntegrationService {

  private static final Set<String> EJD_CONNECTOR_CODES =
      Set.of("QUIPUX", "NOTARIA", "MUNICIPIO", "REGISTRO", "FIRMA", "PAGOS");

  private static final Set<String> ECD_CONNECTOR_CODES =
      Set.of("QUIPUX", "REGISTRO", "FIRMA", "PAGOS", "NOTARIA");

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final EjdStageIntegrationRepository stageIntegrations;
  private final IntegrationRepository integrations;
  private final IntegrationCallRepository integrationCalls;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeService configChanges;

  public EjdIntegrationService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      EjdStageIntegrationRepository stageIntegrations,
      IntegrationRepository integrations,
      IntegrationCallRepository integrationCalls,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeService configChanges) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.stageIntegrations = stageIntegrations;
    this.integrations = integrations;
    this.integrationCalls = integrationCalls;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public List<IntegrationConnectorRef> listEscrituracionConnectors(UUID tenantId) {
    return loadConnectorCatalog(tenantId, "EJD");
  }

  @Transactional(readOnly = true)
  public StageIntegrationsAdminView getStageIntegrationsForAdmin() {
    return getStageIntegrationsForAdmin("EJD");
  }

  @Transactional(readOnly = true)
  public StageIntegrationsAdminView getStageIntegrationsForAdmin(String caseType) {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    return buildAdminView(tenantId, normalizeCaseType(caseType));
  }

  @Transactional
  public StageIntegrationsAdminView replaceStageIntegrations(
      String stageCodeParam, EjdIntegrationDtos.ReplaceStageIntegrationsRequest request) {
    return replaceStageIntegrations("EJD", stageCodeParam, request);
  }

  @Transactional
  public StageIntegrationsAdminView replaceStageIntegrations(
      String caseType,
      String stageCodeParam,
      EjdIntegrationDtos.ReplaceStageIntegrationsRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    String normalizedCaseType = normalizeCaseType(caseType);
    String stageCode = normalizeStageCode(stageCodeParam);
    validateStageExists(tenantId, stageCode, normalizedCaseType);

    Set<String> allowed =
        loadConnectorCatalog(tenantId, normalizedCaseType).stream()
            .map(IntegrationConnectorRef::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : request.integrationCodes()) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(code)) {
        throw new AuthException(
            HttpStatus.BAD_REQUEST, "INVALID_CONNECTOR", "Conector no reconocido: " + code);
      }
      normalized.add(code);
    }

    stageIntegrations.deleteByTenantIdAndStageCode(tenantId, stageCode);
    int order = 1;
    for (String integrationCode : normalized) {
      stageIntegrations.save(
          EjdStageIntegration.create(tenantId, stageCode, integrationCode, order++));
    }

    configChanges.markDraftByCaseType(
        tenantId,
        normalizedCaseType,
        ChangeSetDomain.INTEGRATIONS,
        "Integraciones por etapa (" + stageCode + ")",
        stageCode);
    audit("admin.stage_integrations.updated", stageCode);
    return buildAdminView(tenantId, normalizedCaseType);
  }

  @Transactional(readOnly = true)
  public List<StageConnectorItem> connectorsForStage(UUID tenantId, String stageCode) {
    if (stageCode == null || stageCode.isBlank()) {
      return List.of();
    }
    String normalized = stageCode.trim().toLowerCase(Locale.ROOT);
    Map<String, Integration> integrationByCode = loadIntegrations(tenantId, "EJD");
    return stageIntegrations
        .findByTenantIdAndStageCodeOrderBySortOrderAsc(tenantId, normalized)
        .stream()
        .map(
            link -> {
              Integration integration = integrationByCode.get(link.getIntegrationCode());
              if (integration == null) {
                return null;
              }
              return toStageConnector(integration);
            })
        .filter(item -> item != null)
        .toList();
  }

  private StageIntegrationsAdminView buildAdminView(UUID tenantId, String caseType) {
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND,
                        "PROCESS_NOT_FOUND",
                        "Proceso " + caseType + " no encontrado."));

    List<CatalogItemRef> stages =
        stageDefs
            .findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId)
            .stream()
            .map(stage -> new CatalogItemRef(stage.getCode(), stage.getLabel()))
            .toList();

    List<IntegrationConnectorRef> catalog = loadConnectorCatalog(tenantId, caseType);
    Map<String, String> stageLabels =
        stages.stream().collect(Collectors.toMap(CatalogItemRef::code, CatalogItemRef::label));

    Map<String, List<String>> grouped = new LinkedHashMap<>();
    for (EjdStageIntegration row :
        stageIntegrations.findByTenantIdOrderByStageCodeAscSortOrderAsc(tenantId)) {
      grouped.computeIfAbsent(row.getStageCode(), key -> new ArrayList<>());
      grouped.get(row.getStageCode()).add(row.getIntegrationCode());
    }

    List<StageIntegrationLinks> links = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
      String stageCode = entry.getKey();
      List<IntegrationConnectorRef> connectors =
          entry.getValue().stream()
              .map(code -> catalog.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null))
              .filter(item -> item != null)
              .toList();
      links.add(
          new StageIntegrationLinks(
              stageCode, stageLabels.getOrDefault(stageCode, stageCode), connectors));
    }

    return new StageIntegrationsAdminView(stages, catalog, links);
  }

  private List<IntegrationConnectorRef> loadConnectorCatalog(UUID tenantId, String caseType) {
    Map<String, Integration> byCode = loadIntegrations(tenantId, caseType);
    List<IntegrationConnectorRef> result = new ArrayList<>();
    for (String code : connectorCodesFor(caseType)) {
      Integration integration = byCode.get(code);
      if (integration != null) {
        result.add(toConnectorRef(integration));
      }
    }
    return result;
  }

  private Map<String, Integration> loadIntegrations(UUID tenantId, String caseType) {
    Set<String> allowed = connectorCodesFor(caseType);
    return integrations.findByTenantIdOrderByNameAsc(tenantId).stream()
        .filter(item -> allowed.contains(item.getCode()))
        .collect(Collectors.toMap(Integration::getCode, Function.identity(), (a, b) -> a, LinkedHashMap::new));
  }

  private static Set<String> connectorCodesFor(String caseType) {
    return "ECD".equals(caseType) ? ECD_CONNECTOR_CODES : EJD_CONNECTOR_CODES;
  }

  private static String normalizeCaseType(String caseType) {
    if (caseType == null || caseType.isBlank()) {
      return "EJD";
    }
    String normalized = caseType.trim().toUpperCase(Locale.ROOT);
    if (!"EJD".equals(normalized) && !"ECD".equals(normalized)) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_CASE_TYPE", "caseType debe ser EJD o ECD.");
    }
    return normalized;
  }

  private IntegrationConnectorRef toConnectorRef(Integration integration) {
    IntegrationCall lastCall =
        integrationCalls
            .findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
                integration.getId(), integration.getTenantId())
            .orElse(null);
    return new IntegrationConnectorRef(
        integration.getCode(),
        integration.getName(),
        integration.isEnabled(),
        mapCallStatus(lastCall));
  }

  private StageConnectorItem toStageConnector(Integration integration) {
    IntegrationCall lastCall =
        integrationCalls
            .findFirstByIntegrationIdAndTenantIdOrderByCreatedAtDesc(
                integration.getId(), integration.getTenantId())
            .orElse(null);
    return new StageConnectorItem(
        integration.getCode(),
        integration.getName(),
        integration.isEnabled(),
        mapCallStatus(lastCall));
  }

  private static String mapCallStatus(IntegrationCall call) {
    if (call == null) {
      return "NONE";
    }
    return call.getStatus();
  }

  private void validateStageExists(UUID tenantId, String stageCode, String caseType) {
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND,
                        "PROCESS_NOT_FOUND",
                        "Proceso " + caseType + " no encontrado."));
    boolean exists =
        stageDefs
            .findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId)
            .stream()
            .anyMatch(stage -> stageCode.equals(stage.getCode()));
    if (!exists) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_STAGE", "Etapa no reconocida: " + stageCode);
    }
  }

  private static String normalizeStageCode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_STAGE", "Etapa requerida.");
    }
    return raw.trim().toLowerCase(Locale.ROOT);
  }

  private void requireProcessRead() {
    if (authorization.hasPermission("admin:proceso:leer")
        || authorization.hasPermission("admin:proceso:escribir")
        || authorization.hasPermission("admin:tenant:leer")) {
      return;
    }
    authorization.requirePermission("admin:proceso:leer");
  }

  private void audit(String action, String stageCode) {
    UUID tenantId = AuthContext.require().tenantId();
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "ejd_stage_integration",
            null,
            "OK",
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
