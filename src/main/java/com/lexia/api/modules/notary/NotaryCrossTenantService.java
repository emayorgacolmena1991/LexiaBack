package com.lexia.api.modules.notary;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notifications.BrevoProperties;
import com.lexia.api.modules.notifications.EmailNotificationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryCrossTenantService {

  private static final List<String> TERMINAL_ESTADOS =
      List.of(
          NotaryRemisionEstado.DEVUELTO_AL_BUFETE.name(),
          NotaryRemisionEstado.NOTARIA_COMPLETADA.name());

  private final AuthorizationService authorization;
  private final NotaryAccessGuard accessGuard;
  private final TenantRepository tenants;
  private final NotaryTenantProfileRepository profiles;
  private final TenantNotaryPartnershipRepository partnerships;
  private final NotaryFirmAssignmentRepository assignments;
  private final NotaryRemisionRepository remisiones;
  private final NotaryRemisionDocumentRepository remisionDocuments;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository documentVersions;
  private final ExpedienteDocumentoStorage documentStorage;
  private final AuditEventRepository auditEvents;
  private final EmailNotificationService emails;
  private final AppUserRepository users;
  private final BrevoProperties brevoProperties;

  public NotaryCrossTenantService(
      AuthorizationService authorization,
      NotaryAccessGuard accessGuard,
      TenantRepository tenants,
      NotaryTenantProfileRepository profiles,
      TenantNotaryPartnershipRepository partnerships,
      NotaryFirmAssignmentRepository assignments,
      NotaryRemisionRepository remisiones,
      NotaryRemisionDocumentRepository remisionDocuments,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      LegalDocumentRepository documents,
      DocumentVersionRepository documentVersions,
      ExpedienteDocumentoStorage documentStorage,
      AuditEventRepository auditEvents,
      EmailNotificationService emails,
      AppUserRepository users,
      BrevoProperties brevoProperties) {
    this.authorization = authorization;
    this.accessGuard = accessGuard;
    this.tenants = tenants;
    this.profiles = profiles;
    this.partnerships = partnerships;
    this.assignments = assignments;
    this.remisiones = remisiones;
    this.remisionDocuments = remisionDocuments;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.documents = documents;
    this.documentVersions = documentVersions;
    this.documentStorage = documentStorage;
    this.auditEvents = auditEvents;
    this.emails = emails;
    this.users = users;
    this.brevoProperties = brevoProperties;
  }

  @Transactional(readOnly = true)
  public List<NotaryDtos.NotaryCatalogItemDto> catalogForFirm() {
    if (!authorization.hasPermission("escrituracion:notaria:asignar")) {
      authorization.requirePermission("escrituracion:notaria:enviar");
    }
    UUID firmId = AuthContext.require().tenantId();
    accessGuard.requireFirmContext(firmId);
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
    TenantNotaryPartnership partnership =
        partnerships
            .findById(partnershipId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Vínculo no encontrado."));
    accessGuard.requireNotaryContext(partnership.getNotaryTenantId());
    if (!partnership.getNotaryTenantId().equals(notaryId)) {
      throw NotaryBusinessException.forbidden("Solo la notaría destino puede aprobar el vínculo.");
    }
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
    authorization.requirePermission("notaria:remision:leer");
    NotaryRemision remision =
        remisiones
            .findById(remisionId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
    accessGuard.requireRemisionAccess(remision);
    NotaryRemisionDocument snapshot =
        remisionDocuments
            .findByRemisionIdAndId(remisionId, documentId)
            .orElseThrow(() -> NotaryBusinessException.notFound("Documento no pertenece a la remisión."));
    DocumentVersion version =
        documentVersions
            .findById(snapshot.getFirmDocumentVersionId())
            .filter(v -> v.getTenantId().equals(snapshot.getFirmTenantId()))
            .orElseThrow(() -> NotaryBusinessException.notFound("Versión documental no disponible."));
    byte[] bytes = documentStorage.read(version.getStorageKey());
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
    NotaryRemision remision =
        remisiones
            .findById(id)
            .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
    accessGuard.requireRemisionAccess(remision);
    authorization.requirePermission("notaria:remision:leer");
    return toRemisionDto(remision);
  }

  @Transactional
  public NotaryDtos.RemisionDto getRemisionAndAcknowledge(UUID id) {
    NotaryRemision remision =
        remisiones
            .findById(id)
            .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
    accessGuard.requireRemisionAccess(remision);
    authorization.requirePermission("notaria:remision:actuar");
    if (AuthContext.require().tenantId().equals(remision.getNotaryTenantId())) {
      remision.acknowledgeReceipt();
      remisiones.save(remision);
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
    WritingFile writing =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(legalCase.getId(), firmId)
            .orElse(null);
    List<LegalDocument> docs =
        documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            legalCase.getId(), firmId);
    String actaLabel = pickActaLabel(docs);
    NotaryRemision remision =
        NotaryRemision.create(
            firmId,
            notaryTenantId,
            legalCase.getId(),
            writing == null ? null : writing.getId(),
            legalCase.getCode(),
            legalCase.getSubject(),
            actaLabel,
            docs.size(),
            principal.userId(),
            body.observacionesBufete());
    remisiones.save(remision);
    saveDocumentSnapshots(remision, firmId, docs);
    audit(firmId, "NOTARY_REMISION_CREATED", remision.getId());
    notifyNotary(remision, notaryTenantId);
    return toRemisionDto(remision);
  }

  @Transactional
  public NotaryDtos.RemisionDto advanceRemision(UUID id) {
    authorization.requirePermission("notaria:remision:actuar");
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
    authorization.requirePermission("notaria:remision:actuar");
    NotaryRemision remision = loadForNotaryAction(id);
    remision.devolverAlBufete(body.observacionesNotaria());
    remisiones.save(remision);
    audit(AuthContext.require().tenantId(), "NOTARY_REMISION_RETURNED", id);
    notifyFirmDevolucion(remision);
    return toRemisionDto(remision);
  }

  @Transactional(readOnly = true)
  public Optional<NotaryDtos.RemisionDto> ultimaRemisionForCase(UUID legalCaseId) {
    authorization.requirePermission("escrituracion:notaria:enviar");
    UUID firmId = AuthContext.require().tenantId();
    accessGuard.requireFirmContext(firmId);
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(legalCaseId, firmId)
        .orElseThrow(() -> NotaryBusinessException.notFound("Expediente no encontrado."));
    return remisiones
        .findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(firmId, legalCaseId)
        .map(this::toRemisionDto);
  }

  @Transactional(readOnly = true)
  public Optional<NotaryDtos.RemisionDto> remisionAbiertaForCase(UUID legalCaseId) {
    UUID firmId = AuthContext.require().tenantId();
    return remisiones
        .findFirstByFirmTenantIdAndFirmLegalCaseIdAndEstadoNotInOrderByEmitidoEnDesc(
            firmId, legalCaseId, TERMINAL_ESTADOS)
        .map(this::toRemisionDto);
  }

  private NotaryRemision loadForNotaryAction(UUID id) {
    NotaryRemision remision =
        remisiones
            .findById(id)
            .orElseThrow(() -> NotaryBusinessException.notFound("Remisión no encontrada."));
    accessGuard.requireRemisionAccess(remision);
    accessGuard.requireNotaryContext(remision.getNotaryTenantId());
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

  private void saveDocumentSnapshots(NotaryRemision remision, UUID firmId, List<LegalDocument> docs) {
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
              firmId,
              version.get().getId(),
              doc.getName(),
              order++,
              size > 0 ? size : null,
              status));
    }
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
    List<NotaryDtos.RemisionDocumentDto> docs =
        remisionDocuments.findByRemisionIdOrderBySortOrderAsc(remision.getId()).stream()
            .map(
                d ->
                    new NotaryDtos.RemisionDocumentDto(
                        d.getId(),
                        d.getDisplayName(),
                        d.getSizeBytes(),
                        d.getStatusLabel(),
                        d.getSortOrder()))
            .toList();
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
        notariaNombre,
        remision.getEmitidoEn(),
        remision.getRecibidoEn(),
        remision.getObservacionesBufete(),
        remision.getObservacionesNotaria(),
        docs);
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
