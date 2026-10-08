package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaPage;
import com.lexia.api.modules.expedientes.escrituracion.ExpedienteEstadoService;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class CaseServiceBandejaTest {

  @Mock private LegalCaseRepository legalCases;
  @Mock private AuthorizationService authorization;
  @Mock private TenantParameterService tenantParameters;
  @Mock private SlaCalendarService slaCalendar;
  @Mock private MembershipRepository memberships;
  @Mock private AppUserRepository users;
  @Mock private CasePartyRepository caseParties;
  @Mock private CaseStageRepository caseStages;
  @Mock private ProcessStageDefRepository stageDefs;
  @Mock private CaseBootstrapService bootstrap;
  @Mock private ExpedienteEstadoService expedienteEstado;

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void bandejaLeeExpedienteRealSinNombresDemo() {
    UUID tenant = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID membershipId = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(userId, UUID.randomUUID(), tenant, membershipId));

    LegalCase legalCase =
        LegalCase.create(
            tenant,
            "LEX-2026-014",
            "EJD",
            "Escrituración",
            "Compraventa hipoteca",
            "ALTA",
            membershipId,
            userId,
            Instant.now().plus(5, ChronoUnit.DAYS));
    legalCase.setOperationTypeCode("COMPRAVENTA");

    when(legalCases.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(legalCase)));
    when(caseParties.findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
            legalCase.getId(), tenant, "CLIENT"))
        .thenReturn(
            Optional.of(
                CaseParty.create(
                    tenant, legalCase.getId(), "CLIENT", "Ana Ruiz", "Comprador", "0102030405")));
    Membership membership = Membership.invited(userId, tenant);
    when(memberships.findById(membershipId)).thenReturn(Optional.of(membership));
    when(users.findById(userId)).thenReturn(Optional.of(AppUser.create("ana.ruiz@lexia.test", "Ana Ruiz")));

    CaseService service =
        new CaseService(
            legalCases,
            authorization,
            tenantParameters,
            slaCalendar,
            memberships,
            users,
            caseParties,
            caseStages,
            stageDefs,
            bootstrap,
            expedienteEstado);

    EscrituracionBandejaPage page = service.bandeja("ana", "En trámite", 0, 20);

    assertEquals(1, page.totalElements());
    assertEquals(1, page.content().size());
    assertEquals("LEX-2026-014", page.content().get(0).codigo());
    assertEquals("Ana Ruiz · COMPRAVENTA", page.content().get(0).clienteOperacion());
    assertEquals("En trámite", page.content().get(0).estado());
    assertEquals("Alta", page.content().get(0).atencion());
    assertEquals("Ana Ruiz", page.content().get(0).responsable());
    assertTrue(page.content().stream().noneMatch(row -> row.clienteOperacion().contains("Demo")));
    verify(authorization).requirePermission("expedientes:caso:leer");
  }
}
