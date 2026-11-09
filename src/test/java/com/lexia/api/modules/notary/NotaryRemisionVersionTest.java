package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.api.ApiExceptionHandler;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraft;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraftRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudy;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class NotaryRemisionVersionTest {

  private static final UUID FIRMA = UUID.randomUUID();
  private static final UUID NOTARIA = UUID.randomUUID();
  private static final UUID USUARIO = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID FICHA = UUID.randomUUID();
  private static final String PAYLOAD = "{\"nombre_vendedor\":\"ACME S.A.\"}";
  private static final byte[] MINUTA = {1, 2, 3};
  private static final byte[] CONTRATO = {4, 5};
  private static final String PAYLOAD_V2 = "{\"nombre_vendedor\":\"ACME CORREGIDA S.A.\"}";
  private static final byte[] MINUTA_V2 = {6, 7, 8};

  @TempDir Path almacenamiento;

  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final NotaryRemisionRepository remisiones = mock(NotaryRemisionRepository.class);
  private final NotaryRemisionVersionRepository versiones =
      mock(NotaryRemisionVersionRepository.class);
  private final NotaryRemisionDocumentRepository documentos =
      mock(NotaryRemisionDocumentRepository.class);
  private final TitleStudyRepository estudios = mock(TitleStudyRepository.class);
  private final MinutaDraftRepository drafts = mock(MinutaDraftRepository.class);
  private final MinutaGenerationService minutas = mock(MinutaGenerationService.class);
  private final LegalDocumentRepository documentosCaso = mock(LegalDocumentRepository.class);
  private final DocumentVersionRepository versionesDocumento = mock(DocumentVersionRepository.class);
  private final AppUserRepository usuarios = mock(AppUserRepository.class);
  private final List<NotaryRemisionVersion> versionesGuardadas = new ArrayList<>();
  private final List<NotaryRemisionDocument> documentosGuardados = new ArrayList<>();
  private final Map<UUID, NotaryRemision> remisionesGuardadas = new HashMap<>();
  private ExpedienteDocumentoStorage storage;
  private AuditEventRepository auditoria;
  private NotaryCrossTenantService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USUARIO, UUID.randomUUID(), FIRMA, UUID.randomUUID()));
    storage = new ExpedienteDocumentoStorage(almacenamiento.toString());
    auditoria = mock(AuditEventRepository.class);

    TenantNotaryPartnership vinculo = mock(TenantNotaryPartnership.class);
    when(vinculo.isActive()).thenReturn(true);
    TenantNotaryPartnershipRepository vinculos = mock(TenantNotaryPartnershipRepository.class);
    when(vinculos.findByFirmTenantIdAndNotaryTenantId(FIRMA, NOTARIA))
        .thenReturn(Optional.of(vinculo));
    NotaryFirmAssignment asignacion = mock(NotaryFirmAssignment.class);
    when(asignacion.isActive()).thenReturn(true);
    NotaryFirmAssignmentRepository asignaciones = mock(NotaryFirmAssignmentRepository.class);
    when(asignaciones.findByTenantIdAndNotaryTenantId(FIRMA, NOTARIA))
        .thenReturn(Optional.of(asignacion));

    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getId()).thenReturn(CASO);
    when(legalCase.getCode()).thenReturn("LEX-2026-001");
    LegalCaseRepository casos = mock(LegalCaseRepository.class);
    when(casos.findByIdAndTenantIdAndDeletedAtIsNull(CASO, FIRMA))
        .thenReturn(Optional.of(legalCase));
    WritingFile ficha = mock(WritingFile.class);
    when(ficha.getId()).thenReturn(FICHA);
    WritingFileRepository fichas = mock(WritingFileRepository.class);
    when(fichas.findByCaseIdAndTenantIdAndDeletedAtIsNull(CASO, FIRMA))
        .thenReturn(Optional.of(ficha));
    when(remisiones.findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
            any(), any(), any()))
        .thenReturn(Optional.empty());
    when(documentosCaso.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, FIRMA))
        .thenReturn(List.of());
    DocumentVersion versionDocumento = mock(DocumentVersion.class);
    when(versionDocumento.getId()).thenReturn(UUID.randomUUID());
    when(versionDocumento.getStorageKey()).thenReturn("sin-archivo");
    when(versionesDocumento.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(any(), any()))
        .thenReturn(Optional.of(versionDocumento));
    TenantRepository tenants = mock(TenantRepository.class);
    when(tenants.findById(any())).thenReturn(Optional.empty());
    NotaryTenantProfileRepository perfiles = mock(NotaryTenantProfileRepository.class);
    when(perfiles.findById(any())).thenReturn(Optional.empty());
    when(remisiones.saveAndFlush(any()))
        .thenAnswer(
            llamada -> {
              NotaryRemision remision = llamada.getArgument(0);
              remisionesGuardadas.put(remision.getId(), remision);
              return remision;
            });
    when(remisiones.findById(any()))
        .thenAnswer(
            llamada -> Optional.ofNullable(remisionesGuardadas.get(llamada.<UUID>getArgument(0))));
    when(versiones.save(any()))
        .thenAnswer(
            llamada -> {
              versionesGuardadas.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    when(versiones.findFirstByRemisionIdOrderByVersionNoDesc(any()))
        .thenAnswer(
            llamada ->
                versionesDe(llamada.getArgument(0)).stream()
                    .max(Comparator.comparingInt(NotaryRemisionVersion::getVersionNo)));
    when(versiones.findByRemisionIdAndVersionNo(any(), anyInt()))
        .thenAnswer(
            llamada ->
                versionesDe(llamada.getArgument(0)).stream()
                    .filter(v -> v.getVersionNo() == llamada.<Integer>getArgument(1))
                    .findFirst());
    when(versiones.findByRemisionIdOrderByVersionNoDesc(any()))
        .thenAnswer(
            llamada ->
                versionesDe(llamada.getArgument(0)).stream()
                    .sorted(Comparator.comparingInt(NotaryRemisionVersion::getVersionNo).reversed())
                    .toList());
    when(documentos.save(any()))
        .thenAnswer(
            llamada -> {
              documentosGuardados.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    when(documentos.findByVersionIdOrderBySortOrderAsc(any()))
        .thenAnswer(llamada -> documentosDe(llamada.getArgument(0)));
    when(documentos.countByVersionId(any()))
        .thenAnswer(llamada -> (long) documentosDe(llamada.getArgument(0)).size());

    service =
        new NotaryCrossTenantService(
            authorization,
            tenants,
            perfiles,
            vinculos,
            asignaciones,
            remisiones,
            documentos,
            versiones,
            estudios,
            drafts,
            minutas,
            casos,
            fichas,
            documentosCaso,
            versionesDocumento,
            storage,
            auditoria,
            mock(EmailNotificationService.class),
            usuarios,
            mock(BrevoProperties.class));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  private List<NotaryRemisionVersion> versionesDe(UUID remisionId) {
    return versionesGuardadas.stream().filter(v -> remisionId.equals(v.getRemisionId())).toList();
  }

  private List<NotaryRemisionDocument> documentosDe(UUID versionId) {
    return documentosGuardados.stream().filter(d -> versionId.equals(d.getVersionId())).toList();
  }

  private static void sesion(UUID tenant) {
    AuthContext.set(new AuthPrincipal(USUARIO, UUID.randomUUID(), tenant, UUID.randomUUID()));
  }

  private void estudio(String estado) {
    TitleStudy study = TitleStudy.create(FIRMA, FICHA);
    study.applyResult(estado, "resumen");
    when(estudios.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(FICHA, FIRMA))
        .thenReturn(Optional.of(study));
  }

  private MinutaDraft draft(String kind, String payload, byte[] docx) {
    MinutaDraft draft = MinutaDraft.create(FIRMA, FICHA, "VIV_HIPOTECADA_BIESS", kind);
    draft.setPayload(payload);
    draft.markGenerated("/data/minutas/" + draft.getId() + "_" + kind.toLowerCase() + ".docx");
    when(minutas.leerDocx(draft)).thenReturn(docx);
    return draft;
  }

  private ApiException enviarYFallar() {
    ApiException error = assertThrows(ApiException.class, this::enviar);
    assertEquals(HttpStatus.CONFLICT, error.getStatus());
    verify(remisiones, never()).saveAndFlush(any());
    verify(versiones, never()).save(any());
    return error;
  }

  private NotaryDtos.RemisionDto enviar() {
    return service.createRemision(new NotaryDtos.CreateRemisionBody(NOTARIA, CASO, "Urgente"));
  }

  private NotaryRemisionVersion versionGuardada() {
    assertEquals(1, versionesGuardadas.size());
    return versionesGuardadas.get(0);
  }

  private void documentosDelCaso(String... nombres) {
    List<LegalDocument> docs = new ArrayList<>();
    for (String nombre : nombres) {
      docs.add(LegalDocument.create(FIRMA, CASO, nombre, null));
    }
    when(documentosCaso.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, FIRMA))
        .thenReturn(docs);
  }

  /** Primer envío con minuta generada y un documento. */
  private NotaryDtos.RemisionDto enviada() {
    estudio("APPROVED");
    documentosDelCaso("escritura.pdf");
    MinutaDraft minuta = draft("MINUTA_COMPRAVENTA", PAYLOAD, MINUTA);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(minuta));
    return enviar();
  }

  /** El bufete corrige la minuta y cambia los documentos antes de editar el envío. */
  private void borradorCorregido() {
    documentosDelCaso("escritura.pdf", "avaluo.pdf");
    MinutaDraft corregido = draft("MINUTA_COMPRAVENTA", PAYLOAD_V2, MINUTA_V2);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(corregido));
  }

  private NotaryDtos.RemisionDto editar(UUID remisionId) {
    return service.editarRemision(remisionId, new NotaryDtos.CrearVersionBody("Corrige la minuta"));
  }

  private static void assertEstado(HttpStatus esperado, ApiException error) {
    assertEquals(esperado, error.getStatus());
  }

  @Test
  void sinAprobacionResponde409ConElEstadoActual() {
    estudio("WITH_OBSERVATIONS");

    assertThat(enviarYFallar().getMessage()).contains("WITH_OBSERVATIONS");
  }

  @Test
  void reviewedNoCuentaComoAprobado() {
    estudio("REVIEWED");

    assertThat(enviarYFallar().getMessage()).contains("REVIEWED");
  }

  @Test
  void sinEstudioResponde409() {
    when(estudios.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(FICHA, FIRMA))
        .thenReturn(Optional.empty());

    assertThat(enviarYFallar().getMessage()).contains("sin estudio");
  }

  @Test
  void sinMinutaResponde409() {
    estudio("APPROVED");
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of());

    assertThat(enviarYFallar().getMessage()).contains("Genera la minuta");
  }

  @Test
  void contratoSolo_NoSustituyeALaMinuta() {
    estudio("APPROVED");
    MinutaDraft contrato = draft("CONTRATO_MUTUO", PAYLOAD, CONTRATO);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(contrato));

    enviarYFallar();
  }

  @Test
  void draftSinGenerarNoCuenta() {
    estudio("APPROVED");
    MinutaDraft pendiente = MinutaDraft.create(FIRMA, FICHA, "VIV", "MINUTA_COMPRAVENTA");
    pendiente.setPayload(PAYLOAD);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(pendiente));

    enviarYFallar();
  }

  @Test
  void casoFelizCreaVersion1ConCopiaDeLaMinuta() {
    estudio("APPROVED");
    MinutaDraft anterior = draft("MINUTA_COMPRAVENTA", "{\"viejo\":true}", new byte[] {9});
    MinutaDraft minuta = draft("MINUTA_COMPRAVENTA", PAYLOAD, MINUTA);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(anterior, minuta));

    NotaryDtos.RemisionDto remision = enviar();

    NotaryRemisionVersion version = versionGuardada();
    assertEquals(1, version.getVersionNo());
    assertEquals(remision.id(), version.getRemisionId());
    assertEquals(PAYLOAD, version.getPayload());
    assertEquals(minuta.getId(), version.getMinutaDraftId());
    assertEquals("minuta_compraventa.docx", version.getMinutaFileName());
    assertThat(storage.read(version.getMinutaStorageRef())).isEqualTo(MINUTA);
    assertThat(version.getMinutaStorageRef())
        .startsWith(almacenamiento.toString())
        .doesNotContain(minuta.getId().toString());
    assertEquals("Urgente", version.getObservacionesBufete());
    assertEquals(USUARIO, version.getCreatedBy());
    assertEquals(1, remision.versionActual());
    assertThat(version.getContratoDraftId()).isNull();
    assertThat(version.getContratoStorageRef()).isNull();
  }

  @Test
  void editarCreaV2ConDocumentosYMinutaPropiosYV1Intacta() {
    UUID id = enviada().id();
    NotaryRemisionVersion v1 = versionGuardada();
    borradorCorregido();

    NotaryDtos.RemisionDto remision = editar(id);

    assertEquals(2, versionesGuardadas.size());
    NotaryRemisionVersion v2 = versionesGuardadas.get(1);
    assertEquals(2, v2.getVersionNo());
    assertEquals(id, v2.getRemisionId());
    assertEquals(PAYLOAD_V2, v2.getPayload());
    assertEquals("Corrige la minuta", v2.getObservacionesBufete());
    assertThat(v2.getMinutaStorageRef()).isNotEqualTo(v1.getMinutaStorageRef());
    assertThat(storage.read(v2.getMinutaStorageRef())).isEqualTo(MINUTA_V2);
    assertThat(documentosDe(v2.getId())).hasSize(2);

    assertEquals(PAYLOAD, v1.getPayload());
    assertEquals("Urgente", v1.getObservacionesBufete());
    assertThat(storage.read(v1.getMinutaStorageRef())).isEqualTo(MINUTA);
    assertThat(documentosDe(v1.getId())).hasSize(1);
    assertEquals(2, remision.versionActual());
    assertEquals(2, remision.documentosCount());
  }

  @Test
  void remisionDtoSoloTraeDocumentosDeLaVersionActual() {
    UUID id = enviada().id();
    borradorCorregido();

    NotaryDtos.RemisionDto remision = editar(id);

    NotaryRemisionVersion v2 = versionesGuardadas.get(1);
    assertThat(remision.documentos())
        .extracting(NotaryDtos.RemisionDocumentDto::id)
        .containsExactlyElementsOf(
            documentosDe(v2.getId()).stream().map(NotaryRemisionDocument::getId).toList());
    assertThat(remision.documentos())
        .extracting(NotaryDtos.RemisionDocumentDto::displayName)
        .containsExactly("escritura.pdf", "avaluo.pdf");
    assertThat(remision.actualizadoEn()).isNotNull();
  }

  @Test
  void editarNotariaCompletadaResponde409() {
    UUID id = enviada().id();
    NotaryRemision remision = remisionesGuardadas.get(id);
    for (int i = 0; i < NotaryProcessSteps.COUNT - 1; i++) {
      remision.advanceStep();
    }
    borradorCorregido();

    assertEstado(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> editar(id)));
    assertEquals(1, versionesGuardadas.size());
  }

  @Test
  void editarDevueltaVuelveAEnviadoYReiniciaElTramite() {
    UUID id = enviada().id();
    NotaryRemision guardada = remisionesGuardadas.get(id);
    guardada.acknowledgeReceipt();
    guardada.advanceStep();
    guardada.devolverAlBufete("Falta el avalúo");
    borradorCorregido();

    NotaryDtos.RemisionDto remision = editar(id);

    assertEquals(NotaryRemisionEstado.ENVIADO_A_NOTARIA.name(), remision.estado());
    assertEquals(0, remision.pasoNotarial());
    assertThat(remision.recibidoEn()).isNull();
    assertEquals("Falta el avalúo", remision.observacionesNotaria());
    assertEquals("Corrige la minuta", remision.observacionesBufete());
    assertEquals(2, remision.versionActual());
  }

  @Test
  void noReabreUnaDevueltaSiHayOtraRemisionAbierta() {
    UUID id = enviada().id();
    remisionesGuardadas.get(id).devolverAlBufete(null);
    NotaryRemision otra =
        NotaryRemision.create(FIRMA, NOTARIA, CASO, FICHA, "X", "Y", "Acta", 0, USUARIO, null);
    when(remisiones.findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
            any(), any(), any()))
        .thenReturn(Optional.of(otra));

    assertEstado(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> editar(id)));
    assertEquals(NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void editarExigeEstudioAprobadoIgualQueElEnvio() {
    UUID id = enviada().id();
    estudio("WITH_OBSERVATIONS");

    ApiException error = assertThrows(ApiException.class, () -> editar(id));

    assertEstado(HttpStatus.CONFLICT, error);
    assertThat(error.getMessage()).contains("WITH_OBSERVATIONS");
    assertEquals(1, versionesGuardadas.size());
  }

  @Test
  void editarRemisionDeOtroBufeteResponde404() {
    UUID id = enviada().id();
    sesion(UUID.randomUUID());

    assertEstado(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> editar(id)));
  }

  @Test
  void notariaDestinoLeeMinutaYPayloadDeCadaVersion() {
    UUID id = enviada().id();
    borradorCorregido();
    editar(id);
    sesion(NOTARIA);

    assertThat(service.leerMinutaVersion(id, 1).bytes()).isEqualTo(MINUTA);
    assertThat(service.leerMinutaVersion(id, 2).bytes()).isEqualTo(MINUTA_V2);
    assertEquals(PAYLOAD, service.leerPayloadVersion(id, 1));
    assertEquals(PAYLOAD_V2, service.leerPayloadVersion(id, 2));
    verify(authorization, atLeastOnce()).requirePermission("notaria:remision:leer");
  }

  @Test
  void bufeteDuenoLeeLasVersiones() {
    UUID id = enviada().id();

    assertThat(service.leerMinutaVersion(id, 1).bytes()).isEqualTo(MINUTA);
    verify(authorization, atLeastOnce()).requirePermission("escrituracion:notaria:enviar");
  }

  @Test
  void otraNotariaYOtroBufeteRecibenNotFound() {
    UUID id = enviada().id();

    for (UUID ajeno : List.of(UUID.randomUUID(), UUID.randomUUID())) {
      sesion(ajeno);
      assertEstado(
          HttpStatus.NOT_FOUND,
          assertThrows(ApiException.class, () -> service.leerMinutaVersion(id, 1)));
      assertEstado(
          HttpStatus.NOT_FOUND,
          assertThrows(ApiException.class, () -> service.leerPayloadVersion(id, 1)));
      assertEstado(
          HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.listarVersiones(id)));
      assertEstado(
          HttpStatus.NOT_FOUND,
          assertThrows(ApiException.class, () -> service.detalleVersion(id, 1)));
    }
  }

  @Test
  void getRemisionATenantAjenoResponde404() {
    UUID id = enviada().id();
    sesion(UUID.randomUUID());

    assertEstado(
        HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.getRemision(id)));
  }

  @Test
  void contenidoDeDocumentoATenantAjenoResponde404() {
    UUID id = enviada().id();
    UUID documento = documentosGuardados.get(0).getId();
    sesion(UUID.randomUUID());

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.readRemisionDocument(id, documento)));
  }

  /** ANALISTA del bufete: tiene notaria:remision:leer, pero ni enviar ni asignar. */
  private void analistaSoloLectura() {
    when(authorization.hasPermission("notaria:remision:leer")).thenReturn(true);
    doThrow(new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permiso"))
        .when(authorization)
        .requirePermission("escrituracion:notaria:enviar");
  }

  @Test
  void analistaDelBufeteConSoloLeerLeeRemisionYVersiones() {
    UUID id = enviada().id();
    analistaSoloLectura();

    assertEquals(id, service.getRemision(id).id());
    assertThat(service.listarVersiones(id)).hasSize(1);
    assertEquals(1, service.detalleVersion(id, 1).versionNo());
    assertThat(service.leerMinutaVersion(id, 1).bytes()).isEqualTo(MINUTA);
    assertEquals(PAYLOAD, service.leerPayloadVersion(id, 1));
  }

  @Test
  void analistaDelBufeteConSoloLeerNoPuedeEditar() {
    UUID id = enviada().id();
    borradorCorregido();
    analistaSoloLectura();

    AuthException error = assertThrows(AuthException.class, () -> editar(id));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    assertEquals(1, versionesGuardadas.size());
  }

  @Test
  void analistaDelBufeteConSoloLeerNoPuedeCrearRemision() {
    analistaSoloLectura();

    AuthException error = assertThrows(AuthException.class, this::enviar);

    assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    verify(remisiones, never()).saveAndFlush(any());
  }

  @Test
  void recibirDesdeTenantAjenoResponde404() {
    UUID id = enviada().id();
    sesion(UUID.randomUUID());

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.getRemisionAndAcknowledge(id)));
    assertEquals(NotaryRemisionEstado.ENVIADO_A_NOTARIA.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void recibirDesdeElBufeteDuenoResponde404() {
    UUID id = enviada().id();

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.getRemisionAndAcknowledge(id)));
    assertEquals(NotaryRemisionEstado.ENVIADO_A_NOTARIA.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void recibirDesdeLaNotariaDestinoMarcaRecibido() {
    UUID id = enviada().id();
    sesion(NOTARIA);

    NotaryDtos.RemisionDto remision = service.getRemisionAndAcknowledge(id);

    assertEquals(NotaryRemisionEstado.RECIBIDO.name(), remision.estado());
    assertThat(remision.recibidoEn()).isNotNull();
    verify(authorization).requirePermission("notaria:remision:actuar");
  }

  @Test
  void recibirDosVecesSoloAuditaLaPrimera() {
    UUID id = enviada().id();
    sesion(NOTARIA);

    service.getRemisionAndAcknowledge(id);
    service.getRemisionAndAcknowledge(id);

    verify(auditoria, times(1)).save(argThat(this::esRecepcion));
    assertEquals(NotaryRemisionEstado.RECIBIDO.name(), remisionesGuardadas.get(id).getEstado());
  }

  private boolean esRecepcion(AuditEvent evento) {
    return "NOTARY_REMISION_RECIBIDA".equals(evento.getEvent());
  }

  @Test
  void avanzarDesdeTenantAjenoOElBufeteResponde404() {
    UUID id = enviada().id();

    for (UUID ajeno : List.of(UUID.randomUUID(), FIRMA)) {
      sesion(ajeno);
      assertEstado(
          HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.advanceRemision(id)));
    }
    assertEquals(0, remisionesGuardadas.get(id).getPasoNotarial());
  }

  @Test
  void devolverDesdeTenantAjenoOElBufeteResponde404() {
    UUID id = enviada().id();
    NotaryDtos.DevolverRemisionBody cuerpo = new NotaryDtos.DevolverRemisionBody("Falta el avalúo");

    for (UUID ajeno : List.of(UUID.randomUUID(), FIRMA)) {
      sesion(ajeno);
      assertEstado(
          HttpStatus.NOT_FOUND,
          assertThrows(ApiException.class, () -> service.devolverRemision(id, cuerpo)));
    }
    assertEquals(NotaryRemisionEstado.ENVIADO_A_NOTARIA.name(), remisionesGuardadas.get(id).getEstado());
  }

  @Test
  void laNotariaDestinoAvanzaYDevuelve() {
    UUID id = enviada().id();
    sesion(NOTARIA);

    assertEquals(1, service.advanceRemision(id).pasoNotarial());
    assertEquals(
        NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name(),
        service.devolverRemision(id, new NotaryDtos.DevolverRemisionBody(null)).estado());
    verify(authorization, atLeastOnce()).requirePermission("notaria:remision:actuar");
  }

  @Test
  void analistaDelBufeteObtieneLaRemisionDelCaso() {
    UUID id = enviada().id();
    when(remisiones.findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(FIRMA, CASO))
        .thenReturn(Optional.of(remisionesGuardadas.get(id)));
    analistaSoloLectura();

    assertEquals(id, service.ultimaRemisionForCase(CASO).orElseThrow().id());
  }

  @Test
  void asignarTambienObtieneLaRemisionDelCaso() {
    UUID id = enviada().id();
    when(remisiones.findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(FIRMA, CASO))
        .thenReturn(Optional.of(remisionesGuardadas.get(id)));
    when(authorization.hasPermission("escrituracion:notaria:asignar")).thenReturn(true);
    doThrow(new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permiso"))
        .when(authorization)
        .requirePermission("escrituracion:notaria:enviar");

    assertEquals(id, service.ultimaRemisionForCase(CASO).orElseThrow().id());
  }

  @Test
  void remisionDelCasoSinPermisosDeLecturaResponde403() {
    doThrow(new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permiso"))
        .when(authorization)
        .requirePermission("escrituracion:notaria:enviar");

    AuthException error =
        assertThrows(AuthException.class, () -> service.ultimaRemisionForCase(CASO));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
  }

  @Test
  void remisionDelCasoDesdeTenantAjenoResponde404() {
    sesion(UUID.randomUUID());

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.ultimaRemisionForCase(CASO)));
  }

  @Test
  void versionInexistenteResponde404() {
    UUID id = enviada().id();

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.leerPayloadVersion(id, 9)));
  }

  @Test
  void versionSinContratoResponde404() {
    UUID id = enviada().id();

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.leerContratoVersion(id, 1)));
  }

  @Test
  void versionMigradaSinMinutaResponde404() {
    NotaryRemision remision =
        NotaryRemision.create(FIRMA, NOTARIA, CASO, FICHA, "X", "Y", "Acta", 0, USUARIO, null);
    remisionesGuardadas.put(remision.getId(), remision);
    versionesGuardadas.add(
        NotaryRemisionVersion.of(
            UUID.randomUUID(),
            remision.getId(),
            1,
            "{}",
            new NotaryRemisionVersion.Archivo(null, null, null),
            null,
            null,
            null,
            null));

    assertEstado(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> service.leerMinutaVersion(remision.getId(), 1)));
    assertEquals("{}", service.leerPayloadVersion(remision.getId(), 1));
  }

  @Test
  void historialListaVersionesConAutorYDocumentos() {
    AppUser usuario = mock(AppUser.class);
    when(usuario.getDisplayName()).thenReturn("Ana Pérez");
    when(usuarios.findById(USUARIO)).thenReturn(Optional.of(usuario));
    UUID id = enviada().id();
    borradorCorregido();
    editar(id);

    List<NotaryDtos.RemisionVersionDto> historial = service.listarVersiones(id);

    assertThat(historial).extracting(NotaryDtos.RemisionVersionDto::versionNo).containsExactly(2, 1);
    assertThat(historial)
        .extracting(NotaryDtos.RemisionVersionDto::cantidadDocumentos)
        .containsExactly(2L, 1L);
    assertEquals("Ana Pérez", historial.get(0).createdBy());
    assertEquals("Corrige la minuta", historial.get(0).observacionesBufete());
    assertThat(service.detalleVersion(id, 1).documentos())
        .extracting(NotaryDtos.RemisionDocumentDto::displayName)
        .containsExactly("escritura.pdf");
  }

  @Test
  void rollbackBorraLosArchivosCopiados() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      enviada();
      Path copia = Path.of(versionGuardada().getMinutaStorageRef());
      assertThat(Files.exists(copia)).isTrue();

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(Files.exists(copia)).isFalse();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void rollbackAlEditarBorraSoloLaCopiaNueva() {
    UUID id = enviada().id();
    Path v1 = Path.of(versionGuardada().getMinutaStorageRef());
    borradorCorregido();
    TransactionSynchronizationManager.initSynchronization();
    try {
      editar(id);
      Path v2 = Path.of(versionesGuardadas.get(1).getMinutaStorageRef());

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(Files.exists(v2)).isFalse();
      assertThat(Files.exists(v1)).isTrue();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void commitConservaLosArchivosCopiados() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      enviada();
      Path copia = Path.of(versionGuardada().getMinutaStorageRef());

      TransactionSynchronizationUtils.triggerAfterCompletion(
          TransactionSynchronization.STATUS_COMMITTED);

      assertThat(Files.exists(copia)).isTrue();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void conflictoConcurrenteNoCreaVersionYResponde409() {
    UUID id = enviada().id();
    borradorCorregido();
    doThrow(new ObjectOptimisticLockingFailureException(NotaryRemision.class, id))
        .when(remisiones)
        .saveAndFlush(any());

    ObjectOptimisticLockingFailureException error =
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> editar(id));

    assertEquals(1, versionesGuardadas.size());
    assertEquals(409, new ApiExceptionHandler().optimisticLock(error).getStatusCode().value());
  }

  @Test
  void contratoGeneradoViajaConLaVersion() {
    estudio("APPROVED");
    MinutaDraft minuta = draft("MINUTA_COMPRAVENTA", PAYLOAD, MINUTA);
    MinutaDraft contrato = draft("CONTRATO_MUTUO", "{\"del_contrato\":true}", CONTRATO);
    when(drafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(FICHA, FIRMA))
        .thenReturn(List.of(minuta, contrato));

    enviar();

    NotaryRemisionVersion version = versionGuardada();
    assertEquals(PAYLOAD, version.getPayload());
    assertEquals(minuta.getId(), version.getMinutaDraftId());
    assertEquals(contrato.getId(), version.getContratoDraftId());
    assertEquals("contrato_mutuo.docx", version.getContratoFileName());
    assertThat(storage.read(version.getMinutaStorageRef())).isEqualTo(MINUTA);
    assertThat(storage.read(version.getContratoStorageRef())).isEqualTo(CONTRATO);
  }
}
