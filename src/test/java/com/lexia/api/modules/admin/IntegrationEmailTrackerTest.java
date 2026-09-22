package com.lexia.api.modules.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.notifications.InAppNotificationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationEmailTrackerTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID INTEGRATION_ID = UUID.fromString("e1000000-0000-7000-8000-000000000001");

  @Mock private IntegrationRepository integrations;
  @Mock private IntegrationCallRepository integrationCalls;
  @Mock private AuditEventRepository auditEvents;
  @Mock private InAppNotificationService inAppNotifications;

  private BrevoProperties brevoProperties;
  private IntegrationEmailTracker tracker;

  @BeforeEach
  void setUp() {
    brevoProperties = new BrevoProperties();
    brevoProperties.setEnabled(false);
    tracker =
        new IntegrationEmailTracker(
            integrations, integrationCalls, auditEvents, brevoProperties, inAppNotifications);
  }

  @Test
  void failMarksCallAndNotifiesAdmins() {
    IntegrationCall call =
        IntegrationCall.outbound(
            TENANT_ID, INTEGRATION_ID, "key", "QUEUED", "invitation:test@lexia.demo", "logging-client");
    UUID callId = call.getId();
    when(integrationCalls.findById(callId)).thenReturn(Optional.of(call));

    tracker.fail(callId, "Brevo API 401", "test@lexia.demo", "invitation");

    verify(integrationCalls).save(call);
    verify(auditEvents).save(any());
    verify(inAppNotifications)
        .onEmailDeliveryFailed(
            eq(TENANT_ID), eq("test@lexia.demo"), eq("invitación"), eq("Brevo API 401"));
  }

  @Test
  void queuePersistsLoggedCallWhenBrevoDisabled() {
    Integration integration = new Integration();
    setField(integration, "id", INTEGRATION_ID);
    when(integrations.findByTenantIdAndCode(TENANT_ID, "EMAIL"))
        .thenReturn(Optional.of(integration));

    tracker.queue(TENANT_ID, "test@lexia.demo", "invitation", "invite:test");

    ArgumentCaptor<IntegrationCall> captor = ArgumentCaptor.forClass(IntegrationCall.class);
    verify(integrationCalls).save(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("LOGGED", captor.getValue().getStatus());
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
