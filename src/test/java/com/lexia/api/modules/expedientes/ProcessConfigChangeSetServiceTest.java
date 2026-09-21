package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.ProcessConfigPublishDtos.ChangeSetState;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessConfigChangeSetServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID PROCESS_ID = UUID.fromString("f4000000-0000-7000-8000-000000000001");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID CHANGE_SET_ID = UUID.randomUUID();

  @Mock private ProcessDefinitionRepository processDefinitions;
  @Mock private ProcessConfigChangeSetRepository changeSets;
  @Mock private ProcessConfigChangeSetItemRepository changeSetItems;
  @Mock private ProcessConfigImpactService impactService;
  @Mock private AuthorizationService authorization;
  @Mock private AuditEventRepository auditEvents;

  @InjectMocks private ProcessConfigChangeSetService service;

  @BeforeEach
  void auth() {
    AuthContext.set(new AuthPrincipal(USER_ID, UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void recordItemCreatesDomainLineOnActiveDraft() {
    ProcessConfigChangeSet draft = new ProcessConfigChangeSet();
    setField(draft, "id", CHANGE_SET_ID);
    setField(draft, "status", "DRAFT");
    when(changeSets.findFirstByTenantIdAndProcessDefinitionIdAndStatusInOrderByCreatedAtDesc(
            TENANT_ID, PROCESS_ID, List.of("DRAFT", "REVIEW", "APPROVED")))
        .thenReturn(Optional.of(draft));
    when(changeSetItems.findByChangeSetIdAndDomain(CHANGE_SET_ID, ChangeSetDomain.CALENDAR))
        .thenReturn(Optional.empty());
    when(changeSetItems.save(any(ProcessConfigChangeSetItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.recordItem(
        TENANT_ID,
        PROCESS_ID,
        "EJD",
        ChangeSetDomain.CALENDAR,
        "Calendario operacional",
        "operational_calendar");

    ArgumentCaptor<ProcessConfigChangeSetItem> captor =
        ArgumentCaptor.forClass(ProcessConfigChangeSetItem.class);
    verify(changeSetItems).save(captor.capture());
    assertEquals(ChangeSetDomain.CALENDAR, captor.getValue().getDomain());
    assertEquals("Calendario operacional", captor.getValue().getSummary());
  }

  @Test
  void toStateIncludesItems() {
    ProcessConfigChangeSet draft = new ProcessConfigChangeSet();
    setField(draft, "id", CHANGE_SET_ID);
    setField(draft, "status", "DRAFT");
    ProcessConfigChangeSetItem item =
        ProcessConfigChangeSetItem.create(
            CHANGE_SET_ID, TENANT_ID, ChangeSetDomain.RULE, "Regla X", "RULE_EJD_X");
    when(changeSetItems.findByChangeSetIdOrderByDomainAsc(CHANGE_SET_ID)).thenReturn(List.of(item));

    ChangeSetState state = service.toState(draft);

    assertEquals(1, state.items().size());
    assertEquals(ChangeSetDomain.RULE, state.items().get(0).domain());
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
