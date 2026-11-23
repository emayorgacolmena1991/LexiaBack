package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaPage;
import com.lexia.api.modules.expedientes.escrituracion.secuencial.ExpedienteEstadoService;
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
import org.mockito.ArgumentCaptor;
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
  @Mock private CaseGateRepository caseGates;

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
    when(caseGates.findCaseIdsWithPendingGates(any(UUID.class), any()))
        .thenReturn(List.of(legalCase.getId()));

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
            expedienteEstado,
            caseGates,
            org.mockito.Mockito.mock(EstatusProcesoRepository.class));

    EscrituracionBandejaPage page = service.bandeja("ana", "En trámite", 0, 20);

    assertEquals(1, page.totalElements());
    assertEquals(1, page.content().size());
    assertEquals("LEX-2026-014", page.content().get(0).codigo());
    assertEquals("Ana Ruiz · COMPRAVENTA", page.content().get(0).clienteOperacion());
    assertEquals("En trámite", page.content().get(0).estado());
    assertEquals("Alta", page.content().get(0).atencion());
    assertEquals("Ana Ruiz", page.content().get(0).responsable());
    assertTrue(page.content().get(0).creado().matches("\\d{2} \\p{L}{3}.* \\d{4}"));
    assertTrue(page.content().get(0).tieneGatesPendientes());
    assertTrue(page.content().stream().noneMatch(row -> row.clienteOperacion().contains("Demo")));
    verify(authorization).requirePermission("expedientes:caso:leer");
  }

  private CaseService servicio() {
    return new CaseService(
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
        expedienteEstado,
        caseGates,
        org.mockito.Mockito.mock(EstatusProcesoRepository.class));
  }

  @Test
  void bandejaNoPasaDelTopeDeFilas() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    when(legalCases.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    servicio().bandeja(null, null, 0, 2000);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(legalCases).findAll(any(Specification.class), pageable.capture());
    assertEquals(CaseService.MAX_BANDEJA, pageable.getValue().getPageSize());
  }

  @Test
  void exportarRespetaElOrdenDeLaGrillaYElTope() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID membershipId = UUID.randomUUID();
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), tenant, membershipId));
    LegalCase a =
        LegalCase.create(tenant, "LEX-A", "EJD", "Escrituración", "A", "ALTA", membershipId, null, null);
    LegalCase b =
        LegalCase.create(tenant, "LEX-B", "EJD", "Escrituración", "B", "ALTA", membershipId, null, null);
    // El repositorio devuelve en otro orden; el Excel sigue el de la grilla.
    when(legalCases.findAll(any(Specification.class))).thenReturn(List.of(a, b));
    when(caseGates.findCaseIdsWithPendingGates(any(UUID.class), any())).thenReturn(List.of());

    byte[] xlsx = servicio().exportarBandeja(List.of(b.getId(), a.getId()));

    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
      var sheet = book.getSheetAt(0);
      assertEquals("LEX-B", sheet.getRow(1).getCell(0).getStringCellValue());
      assertEquals("LEX-A", sheet.getRow(2).getCell(0).getStringCellValue());
    }
    verify(authorization).requirePermission("expedientes:caso:leer");
    List<UUID> demasiados =
        java.util.stream.Stream.generate(UUID::randomUUID).limit(CaseService.MAX_EXPORTAR + 1).toList();
    assertThrows(ApiException.class, () -> servicio().exportarBandeja(demasiados));
  }

  @Test
  void filtroEstadoIdaYVueltaCoincideConLaEtiquetaDeLaFila() {
    for (String etiqueta :
        List.of(
            "En trámite",
            "En revisión",
            "Con observación",
            "Pendiente",
            "Con excepción",
            "Cerrado")) {
      assertEquals(etiqueta, CaseService.mapStatusLabel(CaseService.normalizeStatusFilter(etiqueta)));
    }
    assertEquals("CLOSED", CaseService.normalizeStatusFilter("Resuelto"));
    assertEquals("IN_REVIEW", CaseService.normalizeStatusFilter("en revision"));
    assertEquals(null, CaseService.normalizeStatusFilter(" "));
  }
}
