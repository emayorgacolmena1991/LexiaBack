package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.admin.Integration;
import com.lexia.api.modules.admin.IntegrationCall;
import com.lexia.api.modules.admin.IntegrationCallRepository;
import com.lexia.api.modules.admin.IntegrationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EjdConnectorOutboxProcessorTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID OUTBOX_ID = UUID.randomUUID();
  private static final UUID CALL_ID = UUID.randomUUID();
  private static final UUID INTEGRATION_ID = UUID.randomUUID();

  @Mock private OutboxEventRepository outboxEvents;
  @Mock private IntegrationCallRepository integrationCalls;
  @Mock private IntegrationRepository integrations;
  @Mock private EjdConnectorLiveClient liveClient;
  @Mock private ObjectMapper objectMapper;

  @InjectMocks private EjdConnectorOutboxProcessor processor;

  @Test
  void processMarksCallSucceededAndPublishesOutbox() throws Exception {
    OutboxEvent event = OutboxEvent.create(TENANT_ID, EjdConnectorOutboxProcessor.OUTBOX_TYPE, "{}");
    setField(event, "id", OUTBOX_ID);
    when(outboxEvents.findById(OUTBOX_ID)).thenReturn(Optional.of(event));

    EjdConnectorOutboxPayload payload =
        new EjdConnectorOutboxPayload(
            UUID.randomUUID().toString(),
            "LEX-1",
            "NOTARIA",
            "e5",
            "STAGE_ADVANCE",
            CALL_ID.toString());
    when(objectMapper.readValue("{}", EjdConnectorOutboxPayload.class)).thenReturn(payload);

    IntegrationCall call =
        IntegrationCall.outbound(
            TENANT_ID, INTEGRATION_ID, "key", "PENDING", "req", null);
    setField(call, "id", CALL_ID);
    when(integrationCalls.findByIdAndTenantId(CALL_ID, TENANT_ID)).thenReturn(Optional.of(call));

    Integration integration = new Integration();
    setField(integration, "id", INTEGRATION_ID);
    setField(integration, "enabled", true);
    when(integrations.findByTenantIdAndCode(TENANT_ID, "NOTARIA"))
        .thenReturn(Optional.of(integration));
    when(liveClient.invoke(payload))
        .thenReturn(EjdConnectorLiveClient.LiveInvokeResult.ok("{\"ok\":true}"));

    processor.process(OUTBOX_ID);

    ArgumentCaptor<IntegrationCall> callCaptor = ArgumentCaptor.forClass(IntegrationCall.class);
    verify(integrationCalls).save(callCaptor.capture());
    assertEquals("SUCCEEDED", callCaptor.getValue().getStatus());

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxEvents).save(outboxCaptor.capture());
    assertTrue(outboxCaptor.getValue().isPublished());
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
