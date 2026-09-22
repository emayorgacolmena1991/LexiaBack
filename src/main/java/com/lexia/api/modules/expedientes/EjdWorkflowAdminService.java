package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageGatesRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.ReplaceStageTransitionsRequest;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageGateLinks;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageGatesAdminView;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageTransitionPair;
import com.lexia.api.modules.expedientes.EjdWorkflowAdminDtos.StageTransitionsAdminView;
import com.lexia.api.modules.expedientes.TenantConfigDtos.CatalogItemRef;
import com.lexia.api.modules.expedientes.TenantConfigDtos.GateRef;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdWorkflowAdminService {

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final GateDefRepository gateDefs;
  private final ProcessStageTransitionRepository stageTransitions;
  private final EjdStageGateReqRepository stageGateReqs;
  private final AuthorizationService authorization;
  private final ProcessConfigChangeService configChanges;

  public EjdWorkflowAdminService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      GateDefRepository gateDefs,
      ProcessStageTransitionRepository stageTransitions,
      EjdStageGateReqRepository stageGateReqs,
      AuthorizationService authorization,
      ProcessConfigChangeService configChanges) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.gateDefs = gateDefs;
    this.stageTransitions = stageTransitions;
    this.stageGateReqs = stageGateReqs;
    this.authorization = authorization;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public StageTransitionsAdminView getStageTransitionsForAdmin() {
    return getStageTransitionsForAdmin("EJD");
  }

  @Transactional(readOnly = true)
  public StageTransitionsAdminView getStageTransitionsForAdmin(String caseType) {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    List<CatalogItemRef> stages = loadStages(process.getId(), tenantId);
    List<StageTransitionPair> transitions =
        stageTransitions
            .findByTenantIdAndProcessDefinitionIdOrderByFromStageCodeAsc(tenantId, process.getId())
            .stream()
            .map(row -> new StageTransitionPair(row.getFromStageCode(), row.getToStageCode()))
            .toList();
    return new StageTransitionsAdminView(stages, transitions);
  }

  @Transactional
  public StageTransitionsAdminView replaceStageTransitions(ReplaceStageTransitionsRequest request) {
    return replaceStageTransitions("EJD", request);
  }

  @Transactional
  public StageTransitionsAdminView replaceStageTransitions(
      String caseType, ReplaceStageTransitionsRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    Map<String, String> stageLabels =
        loadStages(process.getId(), tenantId).stream()
            .collect(LinkedHashMap::new, (m, s) -> m.put(s.code(), s.label()), Map::putAll);

    stageTransitions.deleteByTenantIdAndProcessDefinitionId(tenantId, process.getId());
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (StageTransitionPair pair : request.transitions()) {
      if (pair == null || pair.fromStageCode() == null || pair.toStageCode() == null) {
        continue;
      }
      String from = normalizeStage(pair.fromStageCode());
      String to = normalizeStage(pair.toStageCode());
      if (!stageLabels.containsKey(from) || !stageLabels.containsKey(to) || from.equals(to)) {
        throw new AuthException(
            HttpStatus.BAD_REQUEST, "INVALID_TRANSITION", "Transición no válida: " + from + " → " + to);
      }
      String key = from + ">" + to;
      if (seen.add(key)) {
        stageTransitions.save(ProcessStageTransition.create(tenantId, process.getId(), from, to));
      }
    }
    configChanges.markDraftByCaseType(tenantId, caseType);
    return getStageTransitionsForAdmin(caseType);
  }

  @Transactional(readOnly = true)
  public StageGatesAdminView getStageGatesForAdmin() {
    return getStageGatesForAdmin("EJD");
  }

  @Transactional(readOnly = true)
  public StageGatesAdminView getStageGatesForAdmin(String caseType) {
    requireProcessRead();
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    List<CatalogItemRef> stages = loadStages(process.getId(), tenantId);
    List<GateRef> gates =
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId).stream()
            .map(
                g ->
                    new GateRef(
                        g.getId(),
                        g.getCode(),
                        g.getQuestion(),
                        g.getSortOrder(),
                        g.getResponseType(),
                        g.getContinueCriterion(),
                        g.isMandatory(),
                        g.getMessageOk(),
                        g.getMessageFail(),
                        g.isActive()))
            .toList();
    Map<String, String> stageLabels =
        stages.stream().collect(LinkedHashMap::new, (m, s) -> m.put(s.code(), s.label()), Map::putAll);
    Map<String, List<GateRef>> grouped = new LinkedHashMap<>();
    Map<String, GateRef> gateByCode =
        gates.stream().collect(LinkedHashMap::new, (m, g) -> m.put(g.code(), g), Map::putAll);
    java.util.Set<String> stageCodes =
        stages.stream().map(CatalogItemRef::code).collect(java.util.stream.Collectors.toSet());
    for (EjdStageGateReq row : stageGateReqs.findByTenantIdOrderByStageCodeAscSortOrderAsc(tenantId)) {
      if (!stageCodes.contains(row.getStageCode())) {
        continue;
      }
      GateRef gate = gateByCode.get(row.getGateCode());
      if (gate != null) {
        grouped.computeIfAbsent(row.getStageCode(), key -> new ArrayList<>()).add(gate);
      }
    }
    List<StageGateLinks> links = new ArrayList<>();
    for (Map.Entry<String, List<GateRef>> entry : grouped.entrySet()) {
      links.add(
          new StageGateLinks(
              entry.getKey(), stageLabels.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()));
    }
    return new StageGatesAdminView(stages, gates, links);
  }

  @Transactional
  public StageGatesAdminView replaceStageGates(String stageCodeParam, ReplaceStageGatesRequest request) {
    return replaceStageGates("EJD", stageCodeParam, request);
  }

  @Transactional
  public StageGatesAdminView replaceStageGates(
      String caseType, String stageCodeParam, ReplaceStageGatesRequest request) {
    authorization.requirePermission("admin:proceso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseType);
    String stageCode = normalizeStage(stageCodeParam);
    validateStage(process.getId(), tenantId, stageCode);

    List<String> allowed =
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId).stream()
            .map(GateDef::getCode)
            .toList();
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : request.gateCodes()) {
      if (raw == null || raw.isBlank()) continue;
      String code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(code)) {
        throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_GATE", "Gate no reconocido: " + code);
      }
      normalized.add(code);
    }
    stageGateReqs.deleteByTenantIdAndStageCode(tenantId, stageCode);
    stageGateReqs.flush();
    int order = 1;
    for (String gateCode : normalized) {
      stageGateReqs.save(EjdStageGateReq.create(tenantId, stageCode, gateCode, order++));
    }
    configChanges.markDraftByCaseType(tenantId, caseType);
    return getStageGatesForAdmin(caseType);
  }

  private ProcessDefinition requireProcess(UUID tenantId, String caseType) {
    String normalized = caseType != null ? caseType.trim().toUpperCase(Locale.ROOT) : "EJD";
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, normalized)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND, "PROCESS_NOT_FOUND", "Proceso " + normalized + " no encontrado."));
  }

  private List<CatalogItemRef> loadStages(UUID processId, UUID tenantId) {
    return stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
        .map(stage -> new CatalogItemRef(stage.getCode(), stage.getLabel()))
        .toList();
  }

  private void validateStage(UUID processId, UUID tenantId, String stageCode) {
    boolean ok =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .anyMatch(stage -> stageCode.equals(stage.getCode()));
    if (!ok) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_STAGE", "Etapa no reconocida.");
    }
  }

  private static String normalizeStage(String raw) {
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
}
