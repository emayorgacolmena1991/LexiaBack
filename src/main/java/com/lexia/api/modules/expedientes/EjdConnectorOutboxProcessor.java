package com.lexia.api.modules.expedientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.admin.IntegrationCall;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EjdConnectorOutboxProcessor {

  static final String OUTBOX_TYPE = "ejd.connector.invoke";

  private static final Logger LOG = LoggerFactory.getLogger(EjdConnectorOutboxProcessor.class);

  private final OutboxEventRepository outboxEvents;
  private final IntegrationCallRepository integrationCalls;
  private final IntegrationRepository integrations;
  private final EjdConnectorLiveClient liveClient;
  private final ObjectMapper objectMapper;

  public EjdConnectorOutboxProcessor(
      OutboxEventRepository outboxEvents,
      IntegrationCallRepository integrationCalls,
      IntegrationRepository integrations,
      EjdConnectorLiveClient liveClient,
      ObjectMapper objectMapper) {
    this.outboxEvents = outboxEvents;
    this.integrationCalls = integrationCalls;
    this.integrations = integrations;
    this.liveClient = liveClient;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void process(UUID outboxEventId) {
    OutboxEvent event =
        outboxEvents
            .findById(outboxEventId)
            .orElseThrow(() -> new IllegalStateException("Outbox event missing: " + outboxEventId));
    if (event.isPublished()) {
      return;
    }
    if (!OUTBOX_TYPE.equals(event.getEventType())) {
      LOG.warn("Skipping unsupported outbox type {}", event.getEventType());
      event.markPublished();
      outboxEvents.save(event);
      return;
    }

    UUID tenantId = event.getTenantId();
    EjdConnectorOutboxPayload payload = parsePayload(event.getPayload());
    UUID callId = UUID.fromString(payload.integrationCallId());

    IntegrationCall call =
        integrationCalls
            .findByIdAndTenantId(callId, tenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "integration_call missing for outbox " + outboxEventId));

    if ("SUCCEEDED".equals(call.getStatus())) {
      event.markPublished();
      outboxEvents.save(event);
      return;
    }

    String connectorCode = payload.connector().trim().toUpperCase(Locale.ROOT);
    var integration =
        integrations.findByTenantIdAndCode(tenantId, connectorCode).orElse(null);
    if (integration == null || !integration.isEnabled()) {
      call.markFailed("Conector deshabilitado o no encontrado: " + connectorCode);
      integrationCalls.save(call);
      event.markPublished();
      outboxEvents.save(event);
      return;
    }

    var result = liveClient.invoke(payload);
    if (result.success()) {
      call.markSucceeded(result.responseBody());
    } else {
      call.markFailed(result.responseBody());
    }
    integrationCalls.save(call);
    event.markPublished();
    outboxEvents.save(event);
  }

  private EjdConnectorOutboxPayload parsePayload(String json) {
    try {
      return objectMapper.readValue(json, EjdConnectorOutboxPayload.class);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid outbox payload", ex);
    }
  }
}
