package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExpedienteSeguimientoEliminacionTest {

  private final CaseActionRepository actions = mock(CaseActionRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void registraQuienEliminoQueDocumentoYDeQueExpediente() {
    UUID tenant = UUID.randomUUID();
    UUID usuario = UUID.randomUUID();
    UUID caso = UUID.randomUUID();
    UUID documento = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(usuario, UUID.randomUUID(), tenant, UUID.randomUUID()));
    var service =
        new ExpedienteSeguimientoService(
            null, null, null, null, null, actions, auditEvents, null, null);

    service.documentoEliminado(caso, tenant, documento, "CHD.pdf");

    ArgumentCaptor<AuditEvent> evento = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(evento.capture());
    assertEquals(usuario, evento.getValue().getActorUserId());
    assertEquals("DOCUMENTO_ELIMINADO:" + documento, evento.getValue().getEvent());
    assertEquals("legal_case", evento.getValue().getObjectType());
    assertEquals(caso, evento.getValue().getObjectId());
    assertEquals("OK", evento.getValue().getResult());
    ArgumentCaptor<CaseAction> actuacion = ArgumentCaptor.forClass(CaseAction.class);
    verify(actions).save(actuacion.capture());
    assertEquals("Eliminación de documento: CHD.pdf", actuacion.getValue().getTitle());
  }
}
