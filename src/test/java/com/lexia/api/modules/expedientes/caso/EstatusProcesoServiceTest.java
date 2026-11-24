package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notary.NotaryRemisionRepository;
import com.lexia.api.modules.notary.NotaryTenantProfileRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EstatusProcesoServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID MEMBRESIA = UUID.randomUUID();

  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
  private final EstatusProcesoRepository estatus = mock(EstatusProcesoRepository.class);
  private final EstatusEtapaRepository etapas = mock(EstatusEtapaRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final NotaryRemisionRepository remisiones = mock(NotaryRemisionRepository.class);
  private final LegalCase legalCase = mock(LegalCase.class);
  private final EstatusEtapa notaria =
      EstatusEtapa.of(
          TENANT,
          "ENTREGA_NOTARIA",
          "Entrega a notaría",
          null,
          5,
          "Enviado a {notaria} el {fecha}. Instancia {numero_instancia}.");
  private EstatusProcesoService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, MEMBRESIA));
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getResponsibleMembershipId()).thenReturn(MEMBRESIA);
    when(estatus.save(any())).thenAnswer(call -> call.getArgument(0));
    service =
        new EstatusProcesoService(
            authorization,
            legalCases,
            estatus,
            etapas,
            mock(AppUserRepository.class),
            auditEvents,
            remisiones,
            mock(NotaryTenantProfileRepository.class));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void registraElEstatusConPermisoDeEscrituraYAuditaSinElTexto() {
    when(etapas.findByTenantIdAndCodigoAndActivoTrue(TENANT, "ENTREGA_NOTARIA"))
        .thenReturn(Optional.of(notaria));

    EstatusProcesoService.Registro r = service.registrar(CASO, "entrega_notaria", "  Enviado hoy.  ");

    assertEquals("ENTREGA_NOTARIA", r.etapa());
    assertEquals("Entrega a notaría", r.etiqueta());
    assertEquals("Enviado hoy.", r.texto());
    verify(authorization).requirePermission("expedientes:caso:escribir");
    ArgumentCaptor<AuditEvent> evento = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(evento.capture());
    assertEquals("ESTATUS_REGISTRADO:ENTREGA_NOTARIA", evento.getValue().getEvent());
    assertEquals(CASO, evento.getValue().getObjectId());
  }

  @Test
  void textoVacioOEtapaFueraDelCatalogoSon400() {
    when(etapas.findByTenantIdAndCodigoAndActivoTrue(TENANT, "ENTREGA_NOTARIA"))
        .thenReturn(Optional.of(notaria));

    assertThrows(ApiException.class, () -> service.registrar(CASO, "ENTREGA_NOTARIA", "   "));
    assertThrows(ApiException.class, () -> service.registrar(CASO, "OTRA", "texto"));
    assertThrows(
        ApiException.class,
        () -> service.registrar(CASO, "ENTREGA_NOTARIA", "x".repeat(EstatusProcesoService.MAX_TEXTO + 1)));
    verify(estatus, never()).save(any());
  }

  @Test
  void expedienteDeOtroTenantEs404() {
    UUID ajeno = UUID.randomUUID();
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(ajeno, TENANT)).thenReturn(Optional.empty());

    AuthException ex = assertThrows(AuthException.class, () -> service.listar(ajeno));
    assertEquals(404, ex.getStatus().value());
    assertThrows(AuthException.class, () -> service.registrar(ajeno, "ENTREGA_NOTARIA", "texto"));
    verify(estatus, never()).save(any());
  }

  @Test
  void historialMasRecientePrimeroConElNombreDeLaEtapa() {
    EstatusProceso viejo = EstatusProceso.crear(TENANT, CASO, null, "ENTREGA_NOTARIA", "primero");
    EstatusProceso nuevo = EstatusProceso.crear(TENANT, CASO, null, "BAJA", "segundo");
    when(estatus.findByTenantIdAndCaseIdOrderByCreatedAtDesc(TENANT, CASO)).thenReturn(List.of(nuevo, viejo));
    when(etapas.findByTenantId(TENANT)).thenReturn(List.of(notaria));

    List<EstatusProcesoService.Registro> historial = service.listar(CASO);

    assertEquals(List.of("segundo", "primero"), historial.stream().map(EstatusProcesoService.Registro::texto).toList());
    assertEquals("Entrega a notaría", historial.get(1).etiqueta());
    // Una etapa que ya no está en el catálogo se muestra por su código.
    assertEquals("BAJA", historial.get(0).etiqueta());
  }

  @Test
  void textoSugeridoConMarcadoresResueltos() {
    when(etapas.findByTenantIdAndActivoTrueOrderByOrdenAsc(TENANT)).thenReturn(List.of(notaria));
    when(legalCase.getNumeroInstancia()).thenReturn("INS-77");
    when(remisiones.findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(TENANT, CASO))
        .thenReturn(Optional.empty());

    String sugerido = service.etapas(CASO).get(0).sugerido();

    String hoy = LocalDate.now(EstatusProcesoService.ZONA).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
    assertEquals("Enviado a la notaría el " + hoy + ". Instancia INS-77.", sugerido);
    // Sin expediente, el catálogo trae el texto con los marcadores.
    assertTrue(service.etapas(null).get(0).sugerido().contains("{notaria}"));
    assertEquals(
        "A Notaría Primera, 01/02/2026",
        EstatusProcesoService.resolver(
            "A {notaria}, {fecha}", Map.of("notaria", "Notaría Primera", "fecha", "01/02/2026")));
  }
}
