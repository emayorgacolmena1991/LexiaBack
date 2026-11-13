package com.lexia.api.modules.notary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
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
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryCrossTenantService {

  private static final Logger LOG = LoggerFactory.getLogger(NotaryCrossTenantService.class);
  private static final List<String> TERMINAL_ESTADOS =
      List.of(
          NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name(),
          NotaryRemisionEstado.NOTARIA_COMPLETADA.name());
  private static final String ESTUDIO_APROBADO = "APPROVED";
  private static final String KIND_CONTRATO = "CONTRATO_MUTUO";
  private static final String PREFIJO_MINUTA = "MINUTA_";
  private static final int PRIMERA_VERSION = 1;

  private final AuthorizationService authorization;
  private final TenantRepository tenants;
  private final NotaryTenantProfileRepository profiles;
  private final TenantNotaryPartnershipRepository partnerships;
  private final NotaryFirmAssignmentRepository assignments;
  private final NotaryRemisionRepository remisiones;
  private final NotaryRemisionDocumentRepository remisionDocuments;
  private final NotaryRemisionVersionRepository remisionVersions;
  private final TitleStudyRepository titleStudies;
  private final MinutaDraftRepository minutaDrafts;
  private final MinutaGenerationService minutas;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository documentVersions;
  private final ExpedienteDocumentoStorage documentStorage;
  private final AuditEventRepository auditEvents;
  private final EmailNotificationService emails;
  private final AppUserRepository users;
  private final BrevoProperties brevoProperties;
  private final NotaryEscrituraRepository escrituras;
  private final ObjectMapper mapper;

  public NotaryCrossTenantService(
      AuthorizationService authorization,
      TenantRepository tenants,
      NotaryTenantProfileRepository profiles,
      TenantNotaryPartnershipRepository partnerships,
      NotaryFirmAssignmentRepository assignments,
      NotaryRemisionRepository remisiones,
      NotaryRemisionDocumentRepository remisionDocuments,
      NotaryRemisionVersionRepository remisionVersions,
      TitleStudyRepository titleStudies,
      MinutaDraftRepository minutaDrafts,
      MinutaGenerationService minutas,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      LegalDocumentRepository documents,
      DocumentVersionRepository documentVersions,
      ExpedienteDocumentoStorage documentStorage,
      AuditEventRepository auditEvents,
      EmailNotificationService emails,
      AppUserRepository users,
      BrevoProperties brevoProperties,
      NotaryEscrituraRepository escrituras,
      ObjectMapper mapper) {
    this.authorization = authorization;
    this.tenants = tenants;
    this.profiles = profiles;
    this.partnerships = partnerships;
    this.assignments = assignments;
    this.remisiones = remisiones;
    this.remisionDocuments = remisionDocuments;
    this.remisionVersions = remisionVersions;
    this.titleStudies = titleStudies;
    this.minutaDrafts = minutaDrafts;
    this.minutas = minutas;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.documents = documents;
    this.documentVersions = documentVersions;
    this.documentStorage = documentStorage;
    this.auditEvents = auditEvents;
    this.emails = emails;
    this.users = users;
    this.brevoProperties = brevoProperties;
    this.escrituras = escrituras;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.NotaryCatalogItemDto> catalogForFirm() {
    if (!authorization.hasPermission("escrituracion:notaria:asignar")) {
      authorization.requirePermission("escrituracion:notaria:enviar");
    }
    UUID firmId = AuthContext.require().tenantId();
    Set<UUID> assigned =
        assignments.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(firmId).stream()
            .map(NotaryFirmAssignment::getNotaryTenantId)
            .collect(Collectors.toSet());
    return profiles.findListedForFirm(firmId).stream()
        .map(
            p -> {
              Tenant t = tenants.findById(p.getTenantId()).orElse(null);
              var partnership =
                  partnerships.findByFirmTenantIdAndNotaryTenantId(firmId, p.getTenantId());
              return new NotaryDtos.NotaryCatalogItemDto(
                  p.getTenantId(),
                  t == null ? "" : t.getCode(),
                  p.getOfficialName(),
                  p.getCanton(),
                  p.getRuc(),
                  p.getNotaryNumber(),
                  p.getNotaryHolderName(),
                  p.getContactEmail(),
                  assigned.contains(p.getTenantId()),
                  partnership.map(TenantNotaryPartnership::getStatus).orElse("NONE"));
            })
        .toList();
  }

  @Transactional
  public NotaryDtos.PartnershipDto requestPartnership(NotaryDtos.RequestPartnershipBody body) {
    authorization.requirePermission("escrituracion:notaria:asignar");
    UUID firmId = AuthContext.require().tenantId();
    UUID notaryTenantId = resolveNotaryTenant(body.notaryTenantCode(), body.notaryRuc());
    ensureNotaryTenant(notaryTenantId);
    if (firmId.equals(notaryTenantId)) {
      throw NotaryBusinessException.badRequest("El bufete y la notaría deben ser tenants distintos.");
    }
    Optional<TenantNotaryPartnership> existing =
        partnerships.findByFirmTenantIdAndNotaryTenantId(firmId, notaryTenantId);
    if (existing.isPresent() && existing.get().isActive()) {
      throw NotaryBusinessException.conflict("Ya existe un vínculo activo con esta notaría.");
    }
    NotaryTenantProfile profile = profiles.findById(notaryTenantId).orElseThrow();
    TenantNotaryPartnership partnership =
        existing.orElseGet(
            () ->
                partnerships.save(
                    TenantNotaryPartnership.request(
                        firmId, notaryTenantId, profile.getContactEmail())));
    if (NotaryPartnershipStatus.PENDING.name().equals(partnership.getStatus())) {
      audit(firmId, "NOTARY_PARTNERSHIP_REQUESTED", partnership.getId());
    }
    return toPartnershipDto(partnership, profile.getOfficialName());
  }

  @Transactional
  public NotaryDtos.PartnershipDto approvePartnership(UUID partnershipId) {
    authorization.requirePermission("notaria:partnership:gestionar");
    UUID notaryId = AuthContext.require().tenantId();
    // Un vínculo de otra notaría no existe para quien no es su destino: 404, igual que uno inexistente.
    TenantNotaryPartnership partnership =
        partnerships
            .findById(partnershipId)
            .filter(p -> p.getNotaryTenantId().equals(notaryId))
            .orElseThrow(() -> NotaryBusinessException.notFound("Vínculo no encontrado."));
    partnership.approve();
    partnerships.save(partnership);
    audit(notaryId, "NOTARY_PARTNERSHIP_APPROVED", partnershipId);
    String name =
        profiles.findById(notaryId).map(NotaryTenantProfile::getOfficialName).orElse("");
    return toPartnershipDto(partnership, name);
  }

  @Transactional
  public void setAssignment(UUID notaryTenantId, boolean active) {
    authorization.requirePermission("escrituracion:notaria:asignar");
    UUID firmId = AuthContext.require().tenantId();
    TenantNotaryPartnership partnership =
        partnerships
            .findByFirmTenantIdAndNotaryTenantId(firmId, notaryTenantId)
            .filter(TenantNotaryPartnership::isActive)
            .orElseThrow(
                () ->
                    NotaryBusinessException.conflict(
                        "Debe existir un vínculo ACTIVE con la notaría antes de asignarla."));
    NotaryFirmAssignment assignment =
        assignments
            .findByTenantIdAndNotaryTenantId(firmId, notaryTenantId)
            .orElseGet(() -> assignments.save(NotaryFirmAssignment.create(firmId, notaryTenantId, partnership.getId())));
    assignment.setActive(active);
    assignments.save(assignment);
    audit(firmId, active ? "NOTARY_ASSIGNED" : "NOTARY_UNASSIGNED", assignment.getId());
  }

  @Transactional
  public void clearAssignments() {
    authorization.requirePermission("escrituracion:notaria:asignar");
    UUID firmId = AuthContext.require().tenantId();
    for (NotaryFirmAssignment row : assignments.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(firmId)) {
      row.setActive(false);
      assignments.save(row);
    }
    audit(firmId, "NOTARY_ASSIGNMENTS_CLEARED", firmId);
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.PartnershipDto> pendingPartnershipsForNotary() {
    authorization.requirePermission("notaria:partnership:gestionar");
    UUID notaryId = AuthContext.require().tenantId();
    ensureNotaryTenant(notaryId);
    String notaryName =
        profiles.findById(notaryId).map(NotaryTenantProfile::getOfficialName).orElse("");
    return partnerships.findByNotaryTenantIdAndStatus(notaryId, NotaryPartnershipStatus.PENDING.name()).stream()
        .map(p -> toPartnershipDto(p, notaryName))
        .toList();
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDocumentContent readRemisionDocument(UUID remisionId, UUID documentId) {
    remisionLegible(remisionId);
    NotaryRemisionDocument snapshot =
        remisionDocuments
            .findByRemisionIdAndId(remisionId, documentId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Documento no pertenece a la remisión."));
    DocumentVersion version =
        documentVersions
            .findById(snapshot.getFirmDocumentVersionId())
            .filter(v -> v.getTenantId().equals(snapshot.getFirmTenantId()))
            .orElseThrow(() -> NotaryBusinessException.notFound("Versión documental no disponible."));
    // Clave del snapshot; filas anteriores a V101 sin backfill usan la de la versión.
    String clave =
        snapshot.getStorageKey() != null ? snapshot.getStorageKey() : version.getStorageKey();
    byte[] bytes = documentStorage.read(clave);
    String mime =
        version.getMimeType() == null || version.getMimeType().isBlank()
            ? guessMime(snapshot.getDisplayName())
            : version.getMimeType();
    audit(AuthContext.require().tenantId(), "NOTARY_REMISION_DOC_READ", documentId);
    return new NotaryDtos.RemisionDocumentContent(snapshot.getDisplayName(), mime, bytes);
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.RemisionDto> bandejaNotaria() {
    authorization.requirePermission("notaria:remision:leer");
    UUID notaryId = AuthContext.require().tenantId();
    ensureNotaryTenant(notaryId);
    return remisiones.findByNotaryTenantIdOrderByEmitidoEnDesc(notaryId).stream()
        .map(this::toRemisionDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDto getRemision(UUID id) {
    return toRemisionDto(remisionLegible(id));
  }

  @Transactional
  public NotaryDtos.RemisionDto getRemisionAndAcknowledge(UUID id) {
    NotaryRemision remision = remisionDeLaNotaria(id);
    authorization.requirePermission("notaria:remision:actuar");
    if (remision.acknowledgeReceipt()) {
      remisiones.save(remision);
      audit(AuthContext.require().tenantId(), "NOTARY_REMISION_RECIBIDA", id);
    }
    return toRemisionDto(remision);
  }

  @Transactional
  public NotaryDtos.RemisionDto createRemision(NotaryDtos.CreateRemisionBody body) {
    authorization.requirePermission("escrituracion:notaria:enviar");
    AuthPrincipal principal = AuthContext.require();
    UUID firmId = principal.tenantId();
    UUID notaryTenantId = body.notaryTenantId();
    ensurePartnershipAndAssignment(firmId, notaryTenantId);
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(body.legalCaseId(), firmId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Expediente no encontrado."));
    remisiones
        .findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
            firmId, legalCase.getId(), TERMINAL_ESTADOS)
        .ifPresent(
            open -> {
              throw NotaryBusinessException.conflict(
                  "Ya existe una remisión abierta para este expediente.");
            });
    NotaryRemision remision =
        NotaryRemision.create(
            firmId,
            notaryTenantId,
            legalCase.getId(),
            null,
            legalCase.getCode(),
            legalCase.getSubject(),
            null,
            0,
            principal.userId(),
            body.observacionesBufete());
    crearVersion(remision, body.observacionesBufete());
    audit(firmId, "NOTARY_REMISION_CREATED", remision.getId());
    notifyNotary(remision, notaryTenantId);
    return toRemisionDto(remision);
  }

  /** Editar el envío: versión n+1 sobre la misma remisión; las anteriores no cambian. */
  @Transactional
  public NotaryDtos.RemisionDto editarRemision(UUID id, NotaryDtos.CrearVersionBody body) {
    authorization.requirePermission("escrituracion:notaria:enviar");
    UUID firmId = AuthContext.require().tenantId();
    NotaryRemision remision =
        remisiones
            .findById(id)
            .filter(r -> firmId.equals(r.getFirmTenantId()))
            .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
    if (NotaryRemisionEstado.NOTARIA_COMPLETADA.name().equals(remision.getEstado())) {
      throw NotaryBusinessException.conflict(
          "La notaría ya completó el trámite: la remisión no se puede editar.");
    }
    // Reabrir una remisión devuelta no puede dejar dos abiertas para el mismo expediente.
    remisiones
        .findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
            firmId, remision.getFirmLegalCaseId(), TERMINAL_ESTADOS)
        .filter(abierta -> !abierta.getId().equals(remision.getId()))
        .ifPresent(
            abierta -> {
              throw NotaryBusinessException.conflict(
                  "Ya existe otra remisión abierta para este expediente.");
            });
    ensurePartnershipAndAssignment(firmId, remision.getNotaryTenantId());
    remision.reenviar(body.observacionesBufete());
    crearVersion(remision, body.observacionesBufete());
    audit(firmId, "NOTARY_REMISION_VERSION_CREATED", remision.getId());
    return toRemisionDto(remision);
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.RemisionVersionDto> listarVersiones(UUID id) {
    NotaryRemision remision = remisionLegible(id);
    return remisionVersions.findByRemisionIdOrderByVersionNoDesc(remision.getId()).stream()
        .map(
            v ->
                new NotaryDtos.RemisionVersionDto(
                    v.getVersionNo(),
                    v.getCreatedAt(),
                    nombreUsuario(v.getCreatedBy()),
                    v.getObservacionesBufete(),
                    v.getMinutaFileName(),
                    v.getContratoFileName(),
                    remisionDocuments.countByVersionId(v.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionVersionDetalleDto detalleVersion(UUID id, int numero) {
    NotaryRemisionVersion v = versionLegible(id, numero);
    return new NotaryDtos.RemisionVersionDetalleDto(
        v.getVersionNo(),
        v.getCreatedAt(),
        nombreUsuario(v.getCreatedBy()),
        v.getObservacionesBufete(),
        v.getMinutaFileName(),
        v.getContratoFileName(),
        documentosDeVersion(v.getId()));
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDocumentContent leerMinutaVersion(UUID id, int numero) {
    NotaryRemisionVersion v = versionLegible(id, numero);
    return docxDeVersion(v.getMinutaStorageRef(), v.getMinutaFileName(), "minuta");
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDocumentContent leerContratoVersion(UUID id, int numero) {
    NotaryRemisionVersion v = versionLegible(id, numero);
    return docxDeVersion(v.getContratoStorageRef(), v.getContratoFileName(), "contrato");
  }

  @Transactional(readOnly = true)
  public String leerPayloadVersion(UUID id, int numero) {
    return versionLegible(id, numero).getPayload();
  }

  /** Alertas de tarifa e impuestos de la versión vigente: bufete dueño o notaría destino. */
  @Transactional(readOnly = true)
  public List<NotaryDtos.AlertaDto> alertas(UUID id) {
    NotaryRemision remision = remisionLegible(id);
    return remisionVersions
        .findFirstByRemisionIdOrderByVersionNoDesc(remision.getId())
        .map(this::alertasDe)
        .orElse(List.of());
  }

  @Transactional
  public NotaryDtos.RemisionDto advanceRemision(UUID id) {
    NotaryRemision remision = loadForNotaryAction(id);
    if (remision.getPasoNotarial() >= NotaryProcessSteps.COUNT - 1) {
      throw NotaryBusinessException.conflict("El trámite ya está en el último paso.");
    }
    remision.advanceStep();
    remisiones.save(remision);
    audit(AuthContext.require().tenantId(), "NOTARY_REMISION_ADVANCED", id);
    return toRemisionDto(remision);
  }

  @Transactional
  public NotaryDtos.RemisionDto devolverRemision(UUID id, NotaryDtos.DevolverRemisionBody body) {
    NotaryRemision remision = loadForNotaryAction(id);
    remision.devolverAlBufete(body.observacionesNotaria());
    remisiones.save(remision);
    audit(AuthContext.require().tenantId(), "NOTARY_REMISION_RETURNED", id);
    notifyFirmDevolucion(remision);
    return toRemisionDto(remision);
  }

  @Transactional(readOnly = true)
  public Optional<NotaryDtos.RemisionDto> ultimaRemisionForCase(UUID legalCaseId) {
    UUID firmId = AuthContext.require().tenantId();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(legalCaseId, firmId)
        .orElseThrow(() -> NotaryBusinessException.notFound("Expediente no encontrado."));
    exigirLecturaDelBufete();
    return remisiones
        .findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(firmId, legalCaseId)
        .map(this::toRemisionDto);
  }

  /** El permiso va después del tenant: para un tenant ajeno la remisión no existe. */
  private NotaryRemision loadForNotaryAction(UUID id) {
    NotaryRemision remision = remisionDeLaNotaria(id);
    authorization.requirePermission("notaria:remision:actuar");
    if (NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name().equals(remision.getEstado())) {
      throw NotaryBusinessException.conflict("La remisión ya fue devuelta al bufete.");
    }
    return remision;
  }

  private void ensurePartnershipAndAssignment(UUID firmId, UUID notaryTenantId) {
    partnerships
        .findByFirmTenantIdAndNotaryTenantId(firmId, notaryTenantId)
        .filter(TenantNotaryPartnership::isActive)
        .orElseThrow(
            () -> NotaryBusinessException.conflict("No hay vínculo activo con la notaría."));
    assignments
        .findByTenantIdAndNotaryTenantId(firmId, notaryTenantId)
        .filter(NotaryFirmAssignment::isActive)
        .orElseThrow(
            () ->
                NotaryBusinessException.conflict(
                    "La notaría no está asignada en preferencias del bufete."));
  }

  private UUID resolveNotaryTenant(String tenantCode, String ruc) {
    if (tenantCode != null && !tenantCode.isBlank()) {
      Tenant tenant =
          tenants
              .findByCode(tenantCode.trim())
              .orElseThrow(() -> NotaryBusinessException.notFound("Tenant no encontrado."));
      return tenant.getId();
    }
    if (ruc != null && !ruc.isBlank()) {
      return profiles
          .findByRuc(ruc.trim())
          .map(NotaryTenantProfile::getTenantId)
          .orElseThrow(() -> NotaryBusinessException.notFound("Notaría no encontrada por RUC."));
    }
    throw NotaryBusinessException.badRequest("Indique notaryTenantCode o notaryRuc.");
  }

  private void ensureNotaryTenant(UUID tenantId) {
    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Tenant no encontrado."));
    if (!TenantKind.NOTARY.name().equals(tenant.getTenantKind())) {
      throw NotaryBusinessException.badRequest("El tenant destino no es una notaría.");
    }
  }

  /**
   * Única vía para crear una versión: la usan el primer envío y la edición. Valida antes de guardar
   * la remisión y de escribir archivos. El flush de la remisión ocurre antes de numerar: con
   * row_version, de dos ediciones simultáneas solo una pasa de aquí.
   */
  private void crearVersion(NotaryRemision remision, String observacionesBufete) {
    UUID firmId = remision.getFirmTenantId();
    UUID caseId = remision.getFirmLegalCaseId();
    WritingFile writing =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, firmId)
            .orElseThrow(
                () ->
                    NotaryBusinessException.conflict(
                        "El expediente no tiene ficha de escrituración: no se puede enviar a notaría."));
    exigirEstudioAprobado(writing, firmId);
    List<MinutaDraft> drafts =
        minutaDrafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(writing.getId(), firmId);
    MinutaDraft minuta =
        ultimoGenerado(drafts, this::esMinuta)
            .filter(draft -> draft.getPayload() != null && !draft.getPayload().isBlank())
            .orElseThrow(
                () ->
                    NotaryBusinessException.conflict(
                        "Genera la minuta del expediente antes de enviarlo a notaría."));
    MinutaDraft contrato = ultimoGenerado(drafts, this::esContrato).orElse(null);
    byte[] minutaDocx = minutas.leerDocx(minuta);
    byte[] contratoDocx = contrato == null ? null : minutas.leerDocx(contrato);
    List<LegalDocument> docs =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, firmId);
    String acta = pickActaLabel(docs);

    remision.vincularEnvio(writing.getId(), 0, acta);
    remisiones.saveAndFlush(remision);

    int versionNo =
        remisionVersions
            .findFirstByRemisionIdOrderByVersionNoDesc(remision.getId())
            .map(v -> v.getVersionNo() + 1)
            .orElse(PRIMERA_VERSION);
    UUID versionId = UUID.randomUUID();
    remisionVersions.save(
        NotaryRemisionVersion.of(
            versionId,
            remision.getId(),
            versionNo,
            minuta.getPayload(),
            copiarDocx(firmId, remision.getId(), versionNo, minuta, minutaDocx),
            contrato == null
                ? null
                : copiarDocx(firmId, remision.getId(), versionNo, contrato, contratoDocx),
            observacionesBufete,
            AuthContext.require().userId(),
            minuta.getProductCode()));
    int snapshots = saveDocumentSnapshots(remision, versionId, firmId, docs);
    remision.vincularEnvio(writing.getId(), snapshots, acta);
    remisiones.save(remision);
  }

  /** Bufete dueño o notaría destino. Cualquier otro tenant no debe saber que existe: 404. */
  private NotaryRemision remisionLegible(UUID id) {
    UUID tenantId = RemisionesVisibles.tenantDeSesion();
    NotaryRemision remision =
        remisionVisible(
            id,
            r -> tenantId.equals(r.getNotaryTenantId()) || tenantId.equals(r.getFirmTenantId()));
    if (tenantId.equals(remision.getNotaryTenantId())) {
      authorization.requirePermission("notaria:remision:leer");
    } else {
      exigirLecturaDelBufete();
    }
    return remision;
  }

  /** Lectura del bufete: enviar, asignar o leer. Escribir sigue exigiendo enviar. */
  private void exigirLecturaDelBufete() {
    if (!authorization.hasPermission("escrituracion:notaria:asignar")
        && !authorization.hasPermission("notaria:remision:leer")) {
      authorization.requirePermission("escrituracion:notaria:enviar");
    }
  }

  /** Solo la notaría destino: para cualquier otro tenant, incluido el bufete, no existe. */
  private NotaryRemision remisionDeLaNotaria(UUID id) {
    return RemisionesVisibles.deLaNotaria(remisiones, id);
  }

  private NotaryRemision remisionVisible(UUID id, Predicate<NotaryRemision> visible) {
    return RemisionesVisibles.visible(remisiones, id, visible);
  }

  private NotaryRemisionVersion versionLegible(UUID id, int numero) {
    return remisionVersions
        .findByRemisionIdAndVersionNo(remisionLegible(id).getId(), numero)
        .orElseThrow(() -> NotaryBusinessException.notFound("Versión no encontrada."));
  }

  private NotaryDtos.RemisionDocumentContent docxDeVersion(
      String storageRef, String fileName, String tipo) {
    if (storageRef == null) {
      throw NotaryBusinessException.notFound("La versión no tiene " + tipo + ".");
    }
    return new NotaryDtos.RemisionDocumentContent(
        fileName, guessMime(fileName), documentStorage.read(storageRef));
  }

  private String nombreUsuario(UUID userId) {
    return userId == null
        ? null
        : users.findById(userId).map(AppUser::getDisplayName).orElse(null);
  }

  private List<NotaryDtos.RemisionDocumentDto> documentosDeVersion(UUID versionId) {
    return remisionDocuments.findByVersionIdOrderBySortOrderAsc(versionId).stream()
        .map(
            d ->
                new NotaryDtos.RemisionDocumentDto(
                    d.getId(),
                    d.getDisplayName(),
                    d.getSizeBytes(),
                    d.getStatusLabel(),
                    d.getSortOrder(),
                    d.getDocType()))
        .toList();
  }

  private void exigirEstudioAprobado(WritingFile writing, UUID firmId) {
    String estado =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writing.getId(), firmId)
            .map(TitleStudy::getStatus)
            .orElse(null);
    if (!ESTUDIO_APROBADO.equals(estado)) {
      throw NotaryBusinessException.conflict(
          "El estudio de título debe estar aprobado para enviar a notaría (estado actual: "
              + (estado == null ? "sin estudio" : estado)
              + ").");
    }
  }

  private boolean esContrato(MinutaDraft draft) {
    return KIND_CONTRATO.equalsIgnoreCase(draft.getTemplateKind());
  }

  private boolean esMinuta(MinutaDraft draft) {
    String kind = draft.getTemplateKind();
    return kind != null && kind.toUpperCase(Locale.ROOT).startsWith(PREFIJO_MINUTA);
  }

  private static Optional<MinutaDraft> ultimoGenerado(
      List<MinutaDraft> drafts, Predicate<MinutaDraft> filtro) {
    return drafts.stream()
        .filter(draft -> MinutaDraft.STATUS_DRAFT_GENERATED.equals(draft.getStatus()))
        .filter(filtro)
        .reduce((anterior, siguiente) -> siguiente);
  }

  /** Copia propia de la versión: el .docx del borrador puede cambiar después del envío. */
  private NotaryRemisionVersion.Archivo copiarDocx(
      UUID firmId, UUID remisionId, int versionNo, MinutaDraft draft, byte[] docx) {
    String nombre = MinutaGenerationService.nombreGuardado(draft);
    String clave =
        firmId
            + "/remisiones/"
            + remisionId
            + "/v"
            + versionNo
            + "/"
            + UUID.randomUUID()
            + "_"
            + ExpedienteDocumentoStorage.safeName(nombre);
    String ruta = documentStorage.guardar(clave, guessMime(nombre), docx).clave();
    borrarSiHayRollback(ruta);
    return new NotaryRemisionVersion.Archivo(draft.getId(), ruta, nombre);
  }

  private void borrarSiHayRollback(String ruta) {
    documentStorage.borrarSiHayRollback(ruta);
  }

  private int saveDocumentSnapshots(
      NotaryRemision remision, UUID versionId, UUID firmId, List<LegalDocument> docs) {
    int order = 0;
    for (LegalDocument doc : docs) {
      Optional<DocumentVersion> version =
          documentVersions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(doc.getId(), firmId);
      if (version.isEmpty()) {
        continue;
      }
      String status = doc.getName().toLowerCase().contains("acta") ? "Aprobado" : "Incluido";
      long size = documentStorage.size(version.get().getStorageKey());
      remisionDocuments.save(
          NotaryRemisionDocument.of(
              remision.getId(),
              versionId,
              firmId,
              version.get().getId(),
              doc.getName(),
              order++,
              size > 0 ? size : null,
              status,
              // Snapshot: las claves no se sobrescriben, reemplazar en el bufete no la toca.
              version.get().getStorageKey(),
              // Tipo al enviar: la notaría filtra (p. ej. SOPORTE_INGRESOS) sin leer app.document.
              doc.getDocType()));
    }
    return order;
  }

  private static String guessMime(String fileName) {
    if (fileName == null) {
      return "application/octet-stream";
    }
    String lower = fileName.toLowerCase();
    if (lower.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (lower.endsWith(".docx")) {
      return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }
    if (lower.endsWith(".doc")) {
      return "application/msword";
    }
    return "application/octet-stream";
  }

  private String pickActaLabel(List<LegalDocument> docs) {
    return docs.stream()
        .filter(d -> d.getName().toLowerCase().contains("acta"))
        .map(LegalDocument::getName)
        .findFirst()
        .orElse("Acta de escritura — revisión documental aprobada.pdf");
  }

  private NotaryDtos.RemisionDto toRemisionDto(NotaryRemision remision) {
    String notariaNombre =
        profiles
            .findById(remision.getNotaryTenantId())
            .map(NotaryTenantProfile::getOfficialName)
            .orElse("");
    Optional<NotaryRemisionVersion> actual =
        remisionVersions.findFirstByRemisionIdOrderByVersionNoDesc(remision.getId());
    List<NotaryDtos.RemisionDocumentDto> docs =
        actual.map(v -> documentosDeVersion(v.getId())).orElse(List.of());
    int versionActual = actual.map(NotaryRemisionVersion::getVersionNo).orElse(0);
    return new NotaryDtos.RemisionDto(
        remision.getId(),
        remision.getFirmTenantId(),
        remision.getNotaryTenantId(),
        remision.getFirmLegalCaseId(),
        remision.getExpedienteCodigo(),
        remision.getAsunto(),
        remision.getEstado(),
        remision.getPasoNotarial(),
        remision.getActaLabel(),
        remision.getDocumentosCount(),
        versionActual,
        notariaNombre,
        remision.getEmitidoEn(),
        remision.getRecibidoEn(),
        remision.getUpdatedAt(),
        remision.getObservacionesBufete(),
        remision.getObservacionesNotaria(),
        docs,
        actual.map(v -> NotaryAlertas.conTarifaOImpuestos(alertasDe(v))).orElse(false));
  }

  /** Payload y documentos de esta versión: los soportes de versiones anteriores no cuentan. */
  private List<NotaryDtos.AlertaDto> alertasDe(NotaryRemisionVersion version) {
    List<String> tipos =
        remisionDocuments.findByVersionIdOrderBySortOrderAsc(version.getId()).stream()
            .map(NotaryRemisionDocument::getDocType)
            .toList();
    return NotaryAlertas.calcular(
        payload(version), () -> fechaReferencia(version.getRemisionId()), tipos);
  }

  /** Payload ilegible: sin alertas. Solo se registra la versión, nunca el contenido. */
  private JsonNode payload(NotaryRemisionVersion version) {
    String json = version.getPayload();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException ex) {
      LOG.warn(
          "Payload ilegible en la versión {} de la remisión {}: sin alertas",
          version.getVersionNo(),
          version.getRemisionId());
      return null;
    }
  }

  /** Otorgamiento de la última escritura generada; si no hay o no se lee, hoy en Guayaquil. */
  private LocalDate fechaReferencia(UUID remisionId) {
    return escrituras
        .findFirstByRemisionIdOrderByGeneratedAtDesc(remisionId)
        .map(NotaryEscritura::getDatosFormulario)
        .flatMap(this::fechaOtorgamiento)
        .orElseGet(() -> LocalDate.now(NotaryAlertas.ZONA));
  }

  private Optional<LocalDate> fechaOtorgamiento(String datosFormulario) {
    if (datosFormulario == null || datosFormulario.isBlank()) {
      return Optional.empty();
    }
    try {
      return LetrasNotariales.parsearFecha(
          mapper.readTree(datosFormulario).path("fecha_otorgamiento").asText(""));
    } catch (JsonProcessingException ex) {
      return Optional.empty();
    }
  }

  private NotaryDtos.PartnershipDto toPartnershipDto(
      TenantNotaryPartnership partnership, String notaryOfficialName) {
    String firmName =
        tenants.findById(partnership.getFirmTenantId()).map(Tenant::getName).orElse("");
    return new NotaryDtos.PartnershipDto(
        partnership.getId(),
        partnership.getFirmTenantId(),
        partnership.getNotaryTenantId(),
        partnership.getStatus(),
        partnership.getApprovedAt(),
        notaryOfficialName,
        firmName);
  }

  private void notifyNotary(NotaryRemision remision, UUID notaryTenantId) {
    NotaryTenantProfile profile = profiles.findById(notaryTenantId).orElse(null);
    if (profile == null || profile.getContactEmail() == null || profile.getContactEmail().isBlank()) {
      return;
    }
    emails.sendNotariaRemisionFromBufete(
        remision.getFirmTenantId(),
        profile.getContactEmail(),
        profile.getOfficialName(),
        remision.getExpedienteCodigo(),
        remision.getAsunto(),
        "Bufete LEXIA",
        profile.getOfficialName(),
        remision.getObservacionesBufete(),
        brevoProperties.getFrontendUrl() + "/notaria/remision/" + remision.getId());
  }

  private void notifyFirmDevolucion(NotaryRemision remision) {
    UUID userId = remision.getEmitidoPorUserId();
    if (userId == null) {
      return;
    }
    AppUser user = users.findById(userId).orElse(null);
    if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
      return;
    }
    String notariaNombre =
        profiles
            .findById(remision.getNotaryTenantId())
            .map(NotaryTenantProfile::getOfficialName)
            .orElse("Notaría");
    String expedienteUrl =
        brevoProperties.getFrontendUrl()
            + "/expedientes/"
            + remision.getFirmLegalCaseId()
            + "?tab=resumen";
    emails.sendNotariaDevolucionAlBufete(
        remision.getFirmTenantId(),
        user.getEmail(),
        user.getDisplayName(),
        remision.getExpedienteCodigo(),
        remision.getAsunto(),
        notariaNombre,
        remision.getObservacionesNotaria(),
        expedienteUrl);
  }

  private void audit(UUID tenantId, String event, UUID objectId) {
    AuthPrincipal principal = AuthContext.get();
    UUID actor = principal == null ? null : principal.userId();
    auditEvents.save(AuditEvent.of(tenantId, actor, event, "notary_remision", objectId, "OK", null, null));
  }
}
