package com.lexia.api.modules.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AuditAdminServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID SESSION_ID = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("d1000000-0000-7000-8000-000000000001");
  private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
  private static final UUID EVENT_ID = UUID.fromString("f5000000-0000-7000-8000-000000000002");

  @Mock private AuditEventRepository auditEvents;
  @Mock private AppUserRepository appUsers;
  @Mock private AuthorizationService authorization;

  @InjectMocks private AuditAdminService service;

  @BeforeEach
  void setPrincipal() {
    AuthContext.set(new AuthPrincipal(ACTOR_ID, SESSION_ID, TENANT_ID, MEMBERSHIP_ID));
  }

  @AfterEach
  void clearPrincipal() {
    AuthContext.clear();
  }

  @Test
  void summaryReturnsDeniedCountLast24h() {
    when(auditEvents.countByTenantIdAndResultAndCreatedAtAfter(
            eq(TENANT_ID), eq("DENIED"), ArgumentMatchers.any(Instant.class)))
        .thenReturn(3L);

    AdminDtos.AuditSummary summary = service.summary();

    verify(authorization).requirePermission("auditoria:evento:leer");
    assertEquals(3L, summary.deniedLast24h());
  }

  @Test
  void listEventsRequiresPermissionAndMapsRows() {
    AuditEvent event = AuditEvent.of(TENANT_ID, null, "TENANT_SEEDED", "tenant", TENANT_ID, "OK", "127.0.0.1", "test");
    setEventId(event, EVENT_ID);
    when(auditEvents.searchTenantEvents(eq(TENANT_ID), eq(""), eq(""), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(event)));

    AdminDtos.PageResponse<AdminDtos.AuditEventItem> response = service.listEvents(0, 25, null, null);

    verify(authorization).requirePermission("auditoria:evento:leer");
    assertEquals(1, response.total());
    assertEquals("TENANT_SEEDED", response.items().get(0).event());
    assertEquals("Sistema", response.items().get(0).actorDisplayName());
    assertEquals(EVENT_ID, response.items().get(0).id());
  }

  private static void setEventId(AuditEvent event, UUID id) {
    try {
      var field = AuditEvent.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(event, id);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
