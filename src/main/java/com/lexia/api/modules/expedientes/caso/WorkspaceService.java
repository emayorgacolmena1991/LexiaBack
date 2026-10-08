package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ActionItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.AttentionItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.AuditItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.DataItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.DocumentItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ExceptionItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.GateItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.LegalReviewItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.NextActionItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.NoteItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ParticipantItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.SituationItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.StageItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.TaskItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.TimelineItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ValidationItem;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.ValidationSummary;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.WorkspaceResponse;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.ejd.EjdCaseExceptionSyncService;
import com.lexia.api.modules.expedientes.ejd.EjdGateEvaluationService;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationService;
import com.lexia.api.modules.expedientes.ejd.EjdValidationEvaluationService;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.proceso.GateDef;
import com.lexia.api.modules.expedientes.proceso.GateDefRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class WorkspaceService {

  private static final DateTimeFormatter GRID_DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("es-CO"))
          .withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter NOTE_DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.forLanguageTag("es-CO"))
          .withZone(ZoneOffset.UTC);

  private final LegalCaseRepository legalCases;
  private final AuthorizationService authorization;
  private final MembershipRepository memberships;
  private final AppUserRepository users;
  private final ProcessStageDefRepository stageDefs;
  private final CaseStageRepository caseStages;
  private final CasePartyRepository caseParties;
  private final GateDefRepository gateDefs;
  private final CaseGateRepository caseGates;
  private final CaseValidationRepository caseValidations;
  private final LegalDocumentRepository documents;
  private final DocumentVersionRepository documentVersions;
  private final CaseNoteRepository caseNotes;
  private final CaseActionRepository caseActions;
  private final EjdValidationEvaluationService validationEvaluation;
  private final EjdIntegrationService ejdIntegrations;
  private final CaseStageTransitionService stageTransitions;
  private final EjdGateEvaluationService gateEvaluation;
  private final ExtractedDataRepository extractedData;
  private final EjdCaseExceptionSyncService exceptionSync;
  private final CaseExceptionRepository caseExceptions;

  public WorkspaceService(
      LegalCaseRepository legalCases,
      AuthorizationService authorization,
      MembershipRepository memberships,
      AppUserRepository users,
      ProcessStageDefRepository stageDefs,
      CaseStageRepository caseStages,
      CasePartyRepository caseParties,
      GateDefRepository gateDefs,
      CaseGateRepository caseGates,
      CaseValidationRepository caseValidations,
      LegalDocumentRepository documents,
      DocumentVersionRepository documentVersions,
      CaseNoteRepository caseNotes,
      CaseActionRepository caseActions,
      EjdValidationEvaluationService validationEvaluation,
      EjdIntegrationService ejdIntegrations,
      CaseStageTransitionService stageTransitions,
      EjdGateEvaluationService gateEvaluation,
      ExtractedDataRepository extractedData,
      EjdCaseExceptionSyncService exceptionSync,
      CaseExceptionRepository caseExceptions) {
    this.legalCases = legalCases;
    this.authorization = authorization;
    this.memberships = memberships;
    this.users = users;
    this.stageDefs = stageDefs;
    this.caseStages = caseStages;
    this.caseParties = caseParties;
    this.gateDefs = gateDefs;
    this.caseGates = caseGates;
    this.caseValidations = caseValidations;
    this.documents = documents;
    this.documentVersions = documentVersions;
    this.caseNotes = caseNotes;
    this.caseActions = caseActions;
    this.validationEvaluation = validationEvaluation;
    this.ejdIntegrations = ejdIntegrations;
    this.stageTransitions = stageTransitions;
    this.gateEvaluation = gateEvaluation;
    this.extractedData = extractedData;
    this.exceptionSync = exceptionSync;
    this.caseExceptions = caseExceptions;
  }

  @Transactional
  public WorkspaceResponse getWorkspace(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));

    validationEvaluation.refreshForCase(legalCase);
    gateEvaluation.refreshForCase(legalCase);
    exceptionSync.syncForCase(legalCase);

    String responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    Map<UUID, ProcessStageDef> stageDefById = loadStageDefinitions(legalCase.getProcessDefinitionId(), tenantId);
    List<StageItem> stages = buildStages(caseId, tenantId, stageDefById);
    String currentStage = stages.stream()
        .filter(stage -> "current".equals(stage.status()))
        .map(StageItem::label)
        .findFirst()
        .orElse(stages.isEmpty() ? "Recepción" : stages.get(0).label());

    List<ParticipantItem> participants = buildParticipants(caseId, tenantId);
    String clientRef = participants.stream()
        .filter(item -> "client".equals(item.kind()))
        .map(ParticipantItem::name)
        .findFirst()
        .orElse("—");

    List<DocumentItem> documentItems = buildDocuments(caseId, tenantId);
    List<ValidationItem> validations = buildValidations(caseId, tenantId);
    ValidationSummary validationSummary = summarizeValidations(validations);
    List<GateItem> gates = buildGates(legalCase.getProcessDefinitionId(), caseId, tenantId);
    List<NoteItem> notes = buildNotes(caseId, tenantId);
    List<ActionItem> actions = buildActions(caseId, tenantId);
    List<TimelineItem> recentActivity = buildRecentActivity(notes, actions, validations);

    String statusLabel = mapStatusLabel(legalCase.getStatus());
    AttentionItem attention = buildAttention(validations, currentStage, responsible);
    String currentStageCode =
        stages.stream()
            .filter(stage -> "current".equals(stage.status()))
            .map(StageItem::id)
            .findFirst()
            .orElse("");
    List<WorkspaceDtos.StageConnectorItem> stageConnectors =
        CaseWorkflowTypes.isOrchestrated(legalCase.getCaseType())
            ? ejdIntegrations.connectorsForStage(tenantId, currentStageCode).stream()
                .map(
                    item ->
                        new WorkspaceDtos.StageConnectorItem(
                            item.code(), item.name(), item.enabled(), item.lastCallStatus()))
                .toList()
            : List.of();
    WorkspaceDtos.StageTransitionOptions stageTransition =
        stageTransitions.preview(caseId, tenantId);

    return new WorkspaceResponse(
        legalCase.getId(),
        false,
        legalCase.getCode(),
        legalCase.getCaseType(),
        legalCase.getVertical(),
        legalCase.getSubject(),
        statusLabel,
        currentStage,
        responsible,
        GRID_DATE.format(legalCase.getUpdatedAt()),
        clientRef,
        attention,
        stages,
        participants,
        documentItems.stream().limit(3).toList(),
        documentItems,
        buildDataItems(caseId, tenantId),
        validations,
        validationSummary,
        List.of(),
        buildExceptions(caseId, tenantId, responsible),
        actions,
        List.of(),
        new LegalReviewItem("Pendiente", responsible, "—", "—", "—", false),
        new NextActionItem(attention.nextAction(), responsible, "—"),
        new SituationItem(statusLabel, currentStage, attention.headline()),
        recentActivity,
        notes,
        gates,
        stageConnectors,
        stageTransition);
  }

  private Map<UUID, ProcessStageDef> loadStageDefinitions(UUID processDefinitionId, UUID tenantId) {
    if (processDefinitionId == null) {
      return Map.of();
    }
    Map<UUID, ProcessStageDef> map = new HashMap<>();
    for (ProcessStageDef definition :
        stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            processDefinitionId, tenantId)) {
      map.put(definition.getId(), definition);
    }
    return map;
  }

  private List<StageItem> buildStages(
      UUID caseId, UUID tenantId, Map<UUID, ProcessStageDef> stageDefById) {
    List<CaseStage> rows = new ArrayList<>(
        caseStages.findByCaseIdAndTenantIdOrderByStartedAtAsc(caseId, tenantId));
    if (rows.isEmpty()) {
      return List.of();
    }
    rows.sort(
        Comparator.comparingInt(
            row -> {
              ProcessStageDef definition = stageDefById.get(row.getStageDefId());
              return definition != null ? definition.getSortOrder() : 0;
            }));
    List<StageItem> stages = new ArrayList<>();
    for (CaseStage row : rows) {
      ProcessStageDef definition = stageDefById.get(row.getStageDefId());
      if (definition == null || !definition.isActive() || definition.getDeprecatedAt() != null) {
        continue;
      }
      stages.add(
          new StageItem(
              definition.getCode(),
              definition.getLabel(),
              definition.getShortLabel(),
              definition.getLabel(),
              row.getStatus()));
    }
    return stages;
  }

  private List<ParticipantItem> buildParticipants(UUID caseId, UUID tenantId) {
    return caseParties.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(caseId, tenantId)
        .stream()
        .map(
            party ->
                new ParticipantItem(
                    party.getDisplayName(),
                    party.getRoleLabel() != null ? party.getRoleLabel() : "Participante",
                    mapPartyKind(party.getKind()),
                    false))
        .toList();
  }

  private List<DocumentItem> buildDocuments(UUID caseId, UUID tenantId) {
    return documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(caseId, tenantId)
        .stream()
        .map(document -> toDocumentItem(document, tenantId))
        .toList();
  }

  private DocumentItem toDocumentItem(LegalDocument document, UUID tenantId) {
    DocumentVersion version =
        documentVersions
            .findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(document.getId(), tenantId)
            .orElse(null);
    return new DocumentItem(
        document.getId(),
        document.getName(),
        document.getDocType() != null ? document.getDocType() : "Documento",
        version != null ? "v" + version.getVersionNo() : "—",
        version != null ? mapDocumentStatus(version.getStatus()) : "Sin versión",
        version != null ? mapDocumentOrigin(version.getOrigin()) : "—",
        version != null ? mapDocumentProcessing(version.getStatus()) : "—",
        GRID_DATE.format(document.getUpdatedAt()),
        false);
  }

  private List<ValidationItem> buildValidations(UUID caseId, UUID tenantId) {
    return caseValidations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(caseId, tenantId).stream()
        .map(
            validation ->
                new ValidationItem(
                    validation.getId(),
                    validation.getLabel(),
                    validation.getKind(),
                    mapValidationResult(validation.getResult()),
                    validation.getEvidence() != null ? validation.getEvidence() : "—",
                    validation.getRuleVersion() != null ? validation.getRuleVersion() : "—",
                    mapValidationReview(validation.getResult()),
                    false))
        .toList();
  }

  private ValidationSummary summarizeValidations(List<ValidationItem> validations) {
    int completed = 0;
    int pending = 0;
    int observation = 0;
    for (ValidationItem validation : validations) {
      switch (validation.result()) {
        case "Cumple" -> completed++;
        case "Revisión requerida" -> observation++;
        default -> pending++;
      }
    }
    return new ValidationSummary(completed, pending, observation);
  }

  private List<GateItem> buildGates(UUID processDefinitionId, UUID caseId, UUID tenantId) {
    if (processDefinitionId == null) {
      return List.of();
    }
    Map<UUID, GateDef> definitions = new HashMap<>();
    for (GateDef gateDef :
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(processDefinitionId, tenantId)) {
      definitions.put(gateDef.getId(), gateDef);
    }
    return caseGates.findByCaseIdAndTenantIdOrderByGateDefIdAsc(caseId, tenantId).stream()
        .map(
            gate -> {
              GateDef definition = definitions.get(gate.getGateDefId());
              return new GateItem(
                  gate.getId(),
                  definition != null ? definition.getCode() : "—",
                  definition != null ? definition.getQuestion() : "Gate",
                  mapGateResult(gate.getResult()),
                  gate.getResult());
            })
        .sorted(
            Comparator.comparingInt(
                gate ->
                    definitions.values().stream()
                        .filter(def -> def.getQuestion().equals(gate.question()))
                        .map(GateDef::getSortOrder)
                        .findFirst()
                        .orElse(0)))
        .toList();
  }

  private List<NoteItem> buildNotes(UUID caseId, UUID tenantId) {
    return caseNotes.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(caseId, tenantId)
        .stream()
        .map(note -> toNoteItem(note))
        .toList();
  }

  private NoteItem toNoteItem(CaseNote note) {
    AppUser author =
        note.getAuthorUserId() != null
            ? users.findById(note.getAuthorUserId()).orElse(null)
            : null;
    String name = author != null ? author.getDisplayName() : "Sistema";
    return new NoteItem(
        note.getId(),
        name,
        initials(name),
        NOTE_DATE.format(note.getCreatedAt()),
        note.getContent(),
        false);
  }

  private List<ActionItem> buildActions(UUID caseId, UUID tenantId) {
    return caseActions.findByCaseIdAndTenantIdOrderByCreatedAtDesc(caseId, tenantId).stream()
        .map(this::toActionItem)
        .toList();
  }

  private ActionItem toActionItem(CaseAction action) {
    AppUser actor =
        action.getActorUserId() != null
            ? users.findById(action.getActorUserId()).orElse(null)
            : null;
    Instant when = action.getActedAt() != null ? action.getActedAt() : action.getCreatedAt();
    return new ActionItem(
        action.getId(),
        action.getTitle(),
        action.getActionType() != null ? action.getActionType() : "—",
        action.getStatus(),
        GRID_DATE.format(when),
        actor != null ? actor.getDisplayName() : "Sistema",
        "—",
        false);
  }

  private List<TimelineItem> buildRecentActivity(
      List<NoteItem> notes, List<ActionItem> actions, List<ValidationItem> validations) {
    List<TimelineItem> items = new ArrayList<>();
    for (NoteItem note : notes) {
      items.add(
          new TimelineItem(
              "Nota registrada",
              note.content(),
              note.datetime(),
              note.author(),
              null,
              "comment"));
    }
    for (ActionItem action : actions) {
      items.add(
          new TimelineItem(
              action.title(),
              action.type(),
              action.date(),
              action.actor(),
              action.evidence(),
              "state"));
    }
    for (ValidationItem validation : validations) {
      if ("No ejecutada".equals(validation.result())) {
        continue;
      }
      items.add(
          new TimelineItem(
              validation.label(),
              validation.result(),
              "—",
              validation.review(),
              validation.evidence(),
              "validation"));
    }
    return items.stream().limit(8).toList();
  }

  private AttentionItem buildAttention(
      List<ValidationItem> validations, String currentStage, String responsible) {
    ValidationItem pending =
        validations.stream()
            .filter(item -> "Revisión requerida".equals(item.result()) || "No ejecutada".equals(item.result()))
            .findFirst()
            .orElse(null);
    if (pending != null) {
      return new AttentionItem(
          "Requiere atención",
          pending.label(),
          "Revisar validación",
          responsible);
    }
    return new AttentionItem(
        "En curso",
        "Etapa actual: " + currentStage,
        "Continuar expediente",
        responsible);
  }

  private String resolveResponsible(UUID membershipId) {
    if (membershipId == null) {
      return "—";
    }
    Membership membership = memberships.findById(membershipId).orElse(null);
    if (membership == null) {
      return "—";
    }
    AppUser user = users.findById(membership.getUserId()).orElse(null);
    return user != null ? user.getDisplayName() : "—";
  }

  private static String mapPartyKind(String kind) {
    return switch (kind) {
      case "CLIENT" -> "client";
      case "PARTY" -> "party";
      default -> "other";
    };
  }

  private static String mapStatusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "En trámite";
      case "CLOSED" -> "Cerrado";
      default -> status;
    };
  }

  private static String mapValidationResult(String result) {
    return switch (result) {
      case "PASS" -> "Cumple";
      case "FAIL" -> "No cumple";
      case "PENDING" -> "Pendiente";
      case "OBSERVATION" -> "Observación";
      case "REVIEW_REQUIRED" -> "Revisión requerida";
      case "NOT_APPLICABLE" -> "No aplica";
      default -> "No ejecutada";
    };
  }

  private static String mapValidationReview(String result) {
    return switch (result) {
      case "PASS", "FAIL", "NOT_APPLICABLE", "OBSERVATION" -> "Automática";
      case "REVIEW_REQUIRED", "PENDING" -> "Pendiente";
      default -> "Pendiente";
    };
  }

  private static String mapGateResult(String result) {
    return switch (result) {
      case "PASS", "YES" -> "Cumple";
      case "FAIL", "NO" -> "No cumple";
      case "REVIEW_REQUIRED" -> "Revisión pendiente";
      case "NOT_EVALUATED" -> "No evaluado";
      default -> result;
    };
  }

  private static String mapDocumentStatus(String status) {
    return switch (status) {
      case "STORED" -> "Almacenado";
      case "PROCESSING" -> "Procesando";
      case "PROCESSED" -> "Procesado";
      default -> status;
    };
  }

  private static String mapDocumentOrigin(String origin) {
    return switch (origin) {
      case "UPLOAD" -> "Carga manual";
      case "INTEGRATION" -> "Integración";
      default -> origin;
    };
  }

  private static String mapDocumentProcessing(String status) {
    return "PROCESSED".equals(status) ? "Completo" : "Pendiente";
  }

  private static String initials(String name) {
    return java.util.Arrays.stream(name.split("\\s+"))
        .filter(part -> !part.isBlank())
        .limit(2)
        .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
        .reduce("", String::concat);
  }

  private List<ExceptionItem> buildExceptions(UUID caseId, UUID tenantId, String defaultOwner) {
    Instant now = Instant.now();
    return caseExceptions.findByCaseIdAndTenantIdOrderByCreatedAtDesc(caseId, tenantId).stream()
        .map(
            row ->
                new ExceptionItem(
                    row.getId(),
                    row.getTitle(),
                    mapExceptionType(row.getExceptionType()),
                    mapExceptionSeverity(row.getSeverity()),
                    resolveExceptionOwner(row.getOwnerMembershipId(), defaultOwner),
                    formatExceptionAge(row.getCreatedAt(), now),
                    mapExceptionStatus(row.getStatus()),
                    row.getDetail() != null ? row.getDetail() : "—",
                    row.getWhy() != null ? row.getWhy() : "—",
                    row.getEvidence() != null ? row.getEvidence() : "—",
                    row.getResolution() != null ? row.getResolution() : "—",
                    false))
        .toList();
  }

  private String resolveExceptionOwner(UUID membershipId, String fallback) {
    if (membershipId == null) {
      return fallback;
    }
    return resolveResponsible(membershipId);
  }

  private static String mapExceptionType(String type) {
    if ("VALIDATION_FAIL".equals(type)) {
      return "Validación";
    }
    return type != null ? type : "—";
  }

  private static String mapExceptionSeverity(String severity) {
    return switch (severity) {
      case "CRITICA" -> "Crítica";
      case "ALTA" -> "Alta";
      case "MEDIA" -> "Media";
      case "BAJA" -> "Baja";
      default -> severity != null ? severity : "Media";
    };
  }

  private static String mapExceptionStatus(String status) {
    return switch (status) {
      case "OPEN" -> "Abierta";
      case "RESOLVED" -> "Resuelta";
      default -> status;
    };
  }

  private static String formatExceptionAge(Instant createdAt, Instant now) {
    if (createdAt == null) {
      return "—";
    }
    long hours = java.time.Duration.between(createdAt, now).toHours();
    if (hours < 1) {
      return "Hace minutos";
    }
    if (hours < 48) {
      return "Hace " + hours + " h";
    }
    long days = hours / 24;
    return "Hace " + days + " d";
  }

  private List<DataItem> buildDataItems(UUID caseId, UUID tenantId) {
    return extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(caseId, tenantId).stream()
        .map(
            row ->
                new DataItem(
                    row.getId(),
                    row.getFieldLabel(),
                    row.getFieldValue() != null ? row.getFieldValue() : "—",
                    row.getFieldGroup() != null ? row.getFieldGroup() : "General",
                    null,
                    false))
        .toList();
  }
}
