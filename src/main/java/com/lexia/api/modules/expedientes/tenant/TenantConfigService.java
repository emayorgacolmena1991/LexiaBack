package com.lexia.api.modules.expedientes.tenant;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.GateRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.ProcessConfig;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.StageRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.TenantVerticalConfig;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.IntegrationChannelRef;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.OperationDocuments;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.ValidationRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Catalog;
import com.lexia.api.modules.identity.CatalogItem;
import com.lexia.api.modules.identity.CatalogItemRepository;
import com.lexia.api.modules.identity.CatalogRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.ejd.EjdDocumentAdminDtos;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationService;
import com.lexia.api.modules.expedientes.ejd.EjdOperationDocumentReq;
import com.lexia.api.modules.expedientes.ejd.EjdOperationDocumentReqRepository;
import com.lexia.api.modules.expedientes.proceso.GateDefRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigChangeSetService;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinition;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinitionRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.reglas.RuleDef;
import com.lexia.api.modules.expedientes.reglas.RuleDefRepository;
import com.lexia.api.modules.expedientes.proceso.ValidationDef;
import com.lexia.api.modules.expedientes.proceso.ValidationDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class TenantConfigService {

  private static final String CATALOG_ESC_OPERATION = "ESC_OPERATION_TYPE";
  private static final String CATALOG_DOC_TYPE = "DOC_TYPE";

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final GateDefRepository gateDefs;
  private final ValidationDefRepository validationDefs;
  private final CatalogRepository catalogs;
  private final CatalogItemRepository catalogItems;
  private final TenantParameterService tenantParameters;
  private final AuthorizationService authorization;
  private final EjdOperationDocumentReqRepository operationDocumentReqs;
  private final RuleDefRepository ruleDefs;
  private final EjdIntegrationService ejdIntegrations;
  private final ProcessConfigChangeSetService changeSetService;
  private final AppUserRepository users;

  public TenantConfigService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      GateDefRepository gateDefs,
      ValidationDefRepository validationDefs,
      CatalogRepository catalogs,
      CatalogItemRepository catalogItems,
      TenantParameterService tenantParameters,
      AuthorizationService authorization,
      EjdOperationDocumentReqRepository operationDocumentReqs,
      RuleDefRepository ruleDefs,
      EjdIntegrationService ejdIntegrations,
      ProcessConfigChangeSetService changeSetService,
      AppUserRepository users) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.gateDefs = gateDefs;
    this.validationDefs = validationDefs;
    this.catalogs = catalogs;
    this.catalogItems = catalogItems;
    this.tenantParameters = tenantParameters;
    this.authorization = authorization;
    this.operationDocumentReqs = operationDocumentReqs;
    this.ruleDefs = ruleDefs;
    this.ejdIntegrations = ejdIntegrations;
    this.changeSetService = changeSetService;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public EjdDocumentAdminDtos.DocumentRequirementsView getEjdDocumentRequirementsForAdmin() {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    List<CatalogItemRef> operationTypes = loadCatalogItems(tenantId, CATALOG_ESC_OPERATION);
    List<CatalogItemRef> documentTypes = loadCatalogItems(tenantId, CATALOG_DOC_TYPE);
    return new EjdDocumentAdminDtos.DocumentRequirementsView(
        operationTypes, documentTypes, buildDocumentRequirements(tenantId, operationTypes, documentTypes));
  }

  @Transactional(readOnly = true)
  public TenantVerticalConfig getConfig(String caseTypeParam) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    String caseType = normalizeCaseType(caseTypeParam);
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND,
                        "PROCESS_NOT_FOUND",
                        "No hay definición de proceso para " + caseType + "."));
    ProcessConfig processConfig = buildProcessConfig(process, tenantId);
    List<CatalogItemRef> operationTypes = loadCatalogItems(tenantId, CATALOG_ESC_OPERATION);
    List<CatalogItemRef> documentTypes = loadCatalogItems(tenantId, CATALOG_DOC_TYPE);
    List<IntegrationChannelRef> channels =
        "EJD".equals(caseType)
            ? ejdIntegrations.listEscrituracionConnectors(tenantId).stream()
                .map(
                    c ->
                        new IntegrationChannelRef(
                            c.code(), c.name(), c.enabled(), c.lastCallStatus()))
                .toList()
            : List.of();
    return new TenantVerticalConfig(
        verticalLabel(caseType),
        caseType,
        processConfig,
        operationTypes,
        documentTypes,
        buildDocumentRequirements(tenantId, operationTypes, documentTypes),
        channels,
        tenantParameters.getCaseCodePattern(tenantId),
        tenantParameters.getSlaDefaultHours(tenantId));
  }

  @Transactional(readOnly = true)
  public ProcessConfig getProcessConfigForAdmin(String caseTypeParam) {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    String caseType = normalizeCaseType(caseTypeParam);
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND,
                        "PROCESS_NOT_FOUND",
                        "No hay definición de proceso para " + caseType + "."));
    return buildProcessConfig(process, tenantId);
  }

  private ProcessConfig buildProcessConfig(ProcessDefinition process, UUID tenantId) {
    UUID processId = process.getId();
    List<StageRef> stages =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .map(
                stage ->
                    new StageRef(
                        stage.getId(),
                        stage.getCode(),
                        stage.getLabel(),
                        stage.getShortLabel(),
                        stage.getSortOrder(),
                        stage.getSlaHours(),
                        stage.getColorKey()))
            .toList();
    List<GateRef> gates =
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .map(
                gate ->
                    new GateRef(
                        gate.getId(),
                        gate.getCode(),
                        gate.getQuestion(),
                        gate.getSortOrder(),
                        gate.getResponseType(),
                        gate.getContinueCriterion(),
                        gate.isMandatory(),
                        gate.getMessageOk(),
                        gate.getMessageFail(),
                        gate.isActive()))
            .toList();
    List<ValidationDef> validationEntities =
        validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId);
    Set<UUID> ruleIds =
        validationEntities.stream()
            .map(ValidationDef::getRuleDefId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, RuleDef> rulesById =
        ruleIds.isEmpty()
            ? Map.of()
            : ruleDefs.findByTenantIdAndIdIn(tenantId, ruleIds).stream()
                .collect(Collectors.toMap(RuleDef::getId, Function.identity()));
    List<ValidationRef> validations =
        validationEntities.stream()
            .map(
                validation -> {
                  RuleDef rule =
                      validation.getRuleDefId() != null
                          ? rulesById.get(validation.getRuleDefId())
                          : null;
                  return new ValidationRef(
                      validation.getId(),
                      validation.getCode(),
                      validation.getLabel(),
                      validation.getSortOrder(),
                      rule != null ? rule.getCode() : null,
                      rule != null ? rule.getStatus() : null,
                      validation.getDescription(),
                      validation.isActive());
                })
            .toList();
    return new ProcessConfig(
        processId,
        process.getCode(),
        process.getName(),
        process.getCaseType(),
        stages,
        gates,
        validations,
        publicationState(process));
  }

  private ProcessConfigPublishDtos.ProcessPublicationState publicationState(
      ProcessDefinition process) {
    UUID tenantId = process.getTenantId();
    UUID modifiedBy = process.getLastModifiedBy();
    Instant modifiedAt = process.getLastModifiedAt();
    if (modifiedAt == null) {
      modifiedAt = process.getLastPublishedAt();
      modifiedBy = process.getLastPublishedBy();
    }
    return new ProcessConfigPublishDtos.ProcessPublicationState(
        process.getConfigVersion(),
        process.isHasUnpublishedChanges(),
        process.getLastPublishedAt(),
        process.getLastPublishedBy(),
        modifiedAt,
        modifiedBy,
        resolveUserDisplayName(modifiedBy),
        changeSetService.activeState(tenantId, process.getId()));
  }

  private String resolveUserDisplayName(UUID userId) {
    if (userId == null) {
      return null;
    }
    return users.findById(userId).map(AppUser::getDisplayName).orElse(null);
  }

  private List<OperationDocuments> buildDocumentRequirements(
      UUID tenantId,
      List<CatalogItemRef> operationTypes,
      List<CatalogItemRef> documentTypes) {
    Map<String, String> docLabels =
        documentTypes.stream()
            .collect(
                LinkedHashMap::new,
                (map, item) -> map.put(item.code(), item.label()),
                Map::putAll);
    Map<String, String> operationLabels =
        operationTypes.stream()
            .collect(
                LinkedHashMap::new,
                (map, item) -> map.put(item.code(), item.label()),
                Map::putAll);
    Map<String, List<CatalogItemRef>> grouped = new LinkedHashMap<>();
    for (EjdOperationDocumentReq row :
        operationDocumentReqs.findByTenantIdOrderByOperationCodeAscSortOrderAsc(tenantId)) {
      String op = row.getOperationCode();
      grouped.computeIfAbsent(op, key -> new ArrayList<>());
      String docCode = row.getDocumentTypeCode();
      grouped
          .get(op)
          .add(new CatalogItemRef(docCode, docLabels.getOrDefault(docCode, docCode)));
    }
    List<OperationDocuments> result = new ArrayList<>();
    for (Map.Entry<String, List<CatalogItemRef>> entry : grouped.entrySet()) {
      result.add(
          new OperationDocuments(
              entry.getKey(),
              operationLabels.getOrDefault(entry.getKey(), entry.getKey()),
              List.copyOf(entry.getValue())));
    }
    return result;
  }

  private List<CatalogItemRef> loadCatalogItems(UUID tenantId, String catalogCode) {
    return catalogs
        .findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, catalogCode)
        .map(catalog -> activeItems(catalog, tenantId))
        .orElse(List.of());
  }

  private List<CatalogItemRef> activeItems(Catalog catalog, UUID tenantId) {
    return catalogItems
        .findByCatalogIdAndTenantIdOrderBySortOrderAscLabelAsc(catalog.getId(), tenantId)
        .stream()
        .filter(CatalogItem::isActive)
        .map(item -> new CatalogItemRef(item.getCode(), item.getLabel()))
        .toList();
  }

  private static String normalizeCaseType(String raw) {
    if (raw == null || raw.isBlank()) {
      return "EJD";
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("EJD") && !normalized.equals("ECD")) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "INVALID_CASE_TYPE", "caseType debe ser EJD o ECD.");
    }
    return normalized;
  }

  private static String verticalLabel(String caseType) {
    return "EJD".equals(caseType) ? "Escrituración" : "Coactivas";
  }

  private void requireProcessRead() {
    if (authorization.hasPermission("admin:proceso:leer")
        || authorization.hasPermission("admin:proceso:escribir")
        || authorization.hasPermission("admin:tenant:leer")) {
      return;
    }
    authorization.requirePermission("admin:proceso:leer");
  }
}
