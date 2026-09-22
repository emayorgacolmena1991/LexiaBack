package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.admin.Integration;
import com.lexia.api.modules.admin.IntegrationCall;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.ExpedienteDtos.ConnectorInvokeResult;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
@EnableConfigurationProperties(EjdIntegrationProperties.class)
public class EjdConnectorDispatchService {

  private static final String OUTBOX_TYPE = "ejd.connector.invoke";

  private final IntegrationRepository integrations;
  private final IntegrationCallRepository integrationCalls;
  private final EjdStageIntegrationRepository stageIntegrations;
  private final OutboxEventRepository outboxEvents;
  private final LegalCaseRepository legalCases;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final AuthorizationService authorization;
  private final EjdIntegrationProperties properties;
  private final ObjectMapper objectMapper;

  public EjdConnectorDispatchService(
      IntegrationRepository integrations,
      IntegrationCallRepository integrationCalls,
      EjdStageIntegrationRepository stageIntegrations,
      OutboxEventRepository outboxEvents,
      LegalCaseRepository legalCases,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      AuthorizationService authorization,
      EjdIntegrationProperties properties,
      ObjectMapper objectMapper) {
    this.integrations = integrations;
    this.integrationCalls = integrationCalls;
    this.stageIntegrations = stageIntegrations;
    this.outboxEvents = outboxEvents;
    this.legalCases = legalCases;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.authorization = authorization;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void dispatchForStage(LegalCase legalCase, String stageCode, String trigger) {
    if (legalCase == null || !CaseWorkflowTypes.isOrchestrated(legalCase.getCaseType()) || stageCode == null) {
      return;
    }
    UUID tenantId = legalCase.getTenantId();
    String stage = stageCode.trim().toLowerCase(Locale.ROOT);
    List<String> connectorCodes =
        stageIntegrations.findByTenantIdAndStageCodeOrderBySortOrderAsc(tenantId, stage).stream()
            .map(EjdStageIntegration::getIntegrationCode)
            .toList();
    for (String code : connectorCodes) {
      integrations.findByTenantIdAndCode(tenantId, code).filter(Integration::isEnabled).ifPresent(
          integration ->
              invokeInternal(legalCase, integration, stage, trigger, true));
    }
  }

  @Transactional
  public ConnectorInvokeResult invokeManually(UUID caseId, String connectorCodeParam) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    String code = connectorCodeParam.trim().toUpperCase(Locale.ROOT);
    Integration integration =
        integrations
            .findByTenantIdAndCode(tenantId, code)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.BAD_REQUEST, "INVALID_CONNECTOR", "Conector no reconocido."));
    if (!integration.isEnabled()) {
      throw new AuthException(
          HttpStatus.CONFLICT,
          "INTEGRATION_DISABLED",
          "Activa el conector en Administración › Integraciones antes de invocarlo.");
    }
    String stage = resolveCurrentStageCode(legalCase, tenantId);
    IntegrationCall call = invokeInternal(legalCase, integration, stage, "MANUAL", false);
    return new ConnectorInvokeResult(
        integration.getCode(),
        call.getStatus(),
        call.getId(),
        "Invocación registrada para " + legalCase.getCode() + ".");
  }

  private IntegrationCall invokeInternal(
      LegalCase legalCase,
      Integration integration,
      String stageCode,
      String trigger,
      boolean skipIfSucceeded) {
    UUID tenantId = legalCase.getTenantId();
    String idempotencyKey =
        "case:"
            + legalCase.getId()
            + ":stage:"
            + stageCode
            + ":connector:"
            + integration.getCode()
            + ":trigger:"
            + trigger;

    if (skipIfSucceeded) {
      var existing =
          integrationCalls.findByTenantIdAndIntegrationIdAndIdempotencyKey(
              tenantId, integration.getId(), idempotencyKey);
      if (existing.isPresent() && "SUCCEEDED".equals(existing.get().getStatus())) {
        return existing.get();
      }
    }

    String requestSummary =
        legalCase.getCode() + " · etapa " + stageCode + " · " + integration.getCode();
    IntegrationCall call =
        IntegrationCall.outbound(
            tenantId,
            integration.getId(),
            idempotencyKey,
            "PENDING",
            requestSummary,
            null);
    integrationCalls.save(call);

    String payload = buildPayload(legalCase, integration.getCode(), stageCode, trigger, call.getId());
    OutboxEvent outbox = OutboxEvent.create(tenantId, OUTBOX_TYPE, payload);
    outboxEvents.save(outbox);

    if (properties.isStubLive()) {
      call.markSucceeded(stubResponse(integration.getCode(), stageCode));
      integrationCalls.save(call);
      outbox.markPublished();
      outboxEvents.save(outbox);
    }
    return call;
  }

  private String resolveCurrentStageCode(LegalCase legalCase, UUID tenantId) {
    if (legalCase.getProcessDefinitionId() == null) {
      return "unknown";
    }
    UUID processId = legalCase.getProcessDefinitionId();
    var codeByDefId =
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processId, tenantId).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ProcessStageDef::getId, ProcessStageDef::getCode));
    return caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(legalCase.getId(), tenantId).stream()
        .filter(row -> "current".equals(row.getStatus()))
        .map(row -> codeByDefId.get(row.getStageDefId()))
        .filter(code -> code != null)
        .findFirst()
        .orElse("unknown");
  }

  private String buildPayload(
      LegalCase legalCase, String connector, String stageCode, String trigger, UUID callId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("caseId", legalCase.getId().toString());
    body.put("caseCode", legalCase.getCode());
    body.put("connector", connector);
    body.put("stageCode", stageCode);
    body.put("trigger", trigger);
    body.put("integrationCallId", callId.toString());
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException ex) {
      return body.toString();
    }
  }

  private static String stubResponse(String connector, String stageCode) {
    return "{\"simulated\":true,\"connector\":\"" + connector + "\",\"stage\":\"" + stageCode + "\"}";
  }
}
