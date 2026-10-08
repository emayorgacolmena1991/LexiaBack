package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseSummaryItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaPage;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoEscrituracion;
import com.lexia.api.modules.expedientes.escrituracion.ExpedienteEstadoService;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseService {

  private static final DateTimeFormatter GRID_DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("es-CO"))
          .withZone(ZoneOffset.UTC);

  private final LegalCaseRepository legalCases;
  private final AuthorizationService authorization;
  private final TenantParameterService tenantParameters;
  private final SlaCalendarService slaCalendar;
  private final MembershipRepository memberships;
  private final AppUserRepository users;
  private final CasePartyRepository caseParties;
  private final CaseStageRepository caseStages;
  private final ProcessStageDefRepository stageDefs;
  private final CaseBootstrapService bootstrap;
  private final ExpedienteEstadoService expedienteEstado;

  public CaseService(
      LegalCaseRepository legalCases,
      AuthorizationService authorization,
      TenantParameterService tenantParameters,
      SlaCalendarService slaCalendar,
      MembershipRepository memberships,
      AppUserRepository users,
      CasePartyRepository caseParties,
      CaseStageRepository caseStages,
      ProcessStageDefRepository stageDefs,
      CaseBootstrapService bootstrap,
      ExpedienteEstadoService expedienteEstado) {
    this.legalCases = legalCases;
    this.authorization = authorization;
    this.tenantParameters = tenantParameters;
    this.slaCalendar = slaCalendar;
    this.memberships = memberships;
    this.users = users;
    this.caseParties = caseParties;
    this.caseStages = caseStages;
    this.stageDefs = stageDefs;
    this.bootstrap = bootstrap;
    this.expedienteEstado = expedienteEstado;
  }

  @Transactional(readOnly = true)
  public EscrituracionBandejaPage bandeja(String search, String estado, int page, int size) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    int safeSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), 100);
    int safePage = Math.max(page, 0);
    String statusCode = normalizeStatusFilter(estado);
    String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

    Specification<LegalCase> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(cb.equal(root.get("tenantId"), tenantId));
          predicates.add(cb.isNull(root.get("deletedAt")));
          predicates.add(cb.equal(root.get("caseType"), "EJD"));
          if (statusCode != null) {
            predicates.add(cb.equal(cb.upper(root.get("status")), statusCode));
          }
          if (!term.isEmpty()) {
            String like = "%" + term + "%";
            Subquery<UUID> clients = query.subquery(UUID.class);
            Root<CaseParty> party = clients.from(CaseParty.class);
            clients
                .select(party.get("caseId"))
                .where(
                    cb.equal(party.get("caseId"), root.get("id")),
                    cb.equal(party.get("tenantId"), tenantId),
                    cb.isNull(party.get("deletedAt")),
                    cb.like(cb.lower(party.get("displayName")), like));
            predicates.add(
                cb.or(
                    cb.like(cb.lower(root.get("code")), like),
                    cb.like(cb.lower(root.get("subject")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("operationTypeCode"), "")), like),
                    cb.exists(clients)));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };

    Page<LegalCase> result =
        legalCases.findAll(
            spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
    List<EscrituracionBandejaItem> content =
        result.getContent().stream().map(row -> toBandeja(row, tenantId)).toList();
    return new EscrituracionBandejaPage(
        content, result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
  }

  @Transactional(readOnly = true)
  public List<CaseSummaryItem> listCases() {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    return legalCases.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId).stream()
        .map(this::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public CaseDetailItem getCase(UUID caseId) {
    return getCase(caseId.toString());
  }

  @Transactional(readOnly = true)
  public CaseDetailItem getCase(String ref) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = resolveCase(tenantId, ref);
    EstadoEscrituracion estado = expedienteEstado.estadoFlujo(legalCase.getId());
    return toDetail(legalCase, estado);
  }

  @Transactional
  public CaseDetailItem createCase(CreateCaseRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    String subject = normalizeRequired(request.subject(), "El asunto es obligatorio.");
    String vertical = normalizeRequired(request.vertical(), "La vertical es obligatoria.");
    String caseType = resolveCaseType(request.caseType(), vertical);
    String priority = normalizePriority(request.priority());
    String code = allocateCaseCode(tenantId);
    int hours = slaCalendar.resolveSlaHours(tenantId, null);
    Instant slaDue = slaCalendar.computeStageDueAt(tenantId, Instant.now(), hours);
    LegalCase created =
        legalCases.save(
            LegalCase.create(
                tenantId,
                code,
                caseType,
                vertical,
                subject,
                priority,
                principal.membershipId(),
                principal.userId(),
                slaDue));
    bootstrap.bootstrap(created, request, tenantId, principal.userId());
    // Entidad ya managed: el flush de commit persiste productCode/etapa.
    // Segundo save() + WritingFile merge con @Version provocaba 409 concurrente.
    return toDetail(created);
  }

  private LegalCase resolveCase(UUID tenantId, String ref) {
    if (ref == null || ref.isBlank()) {
      throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado.");
    }
    String token = ref.trim();
    try {
      UUID id = UUID.fromString(token);
      return legalCases
          .findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
          .orElseThrow(
              () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    } catch (IllegalArgumentException ignored) {
      return legalCases
          .findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(tenantId, token)
          .orElseThrow(
              () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    }
  }

  private EscrituracionBandejaItem toBandeja(LegalCase legalCase, UUID tenantId) {
    CaseParty client =
        caseParties
            .findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
                legalCase.getId(), tenantId, "CLIENT")
            .orElse(null);
    String cliente = client != null ? client.getDisplayName() : legalCase.getSubject();
    String operacion = legalCase.getOperationTypeCode();
    String clienteOperacion =
        operacion == null || operacion.isBlank() ? cliente : cliente + " · " + operacion.trim();
    String etapa = resolveCurrentStageLabel(legalCase, tenantId);
    Responsible responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    return new EscrituracionBandejaItem(
        legalCase.getId(),
        legalCase.getCode(),
        clienteOperacion,
        etapa == null ? "—" : etapa,
        mapStatusLabel(legalCase.getStatus()),
        atencion(legalCase),
        responsible.name(),
        responsible.initials(),
        legalCase.getSlaDueAt() == null ? "—" : GRID_DATE.format(legalCase.getSlaDueAt()));
  }

  private static String atencion(LegalCase legalCase) {
    Instant due = legalCase.getSlaDueAt();
    if (due != null && !due.isAfter(Instant.now())) {
      return "Vencida";
    }
    return mapPriorityLabel(legalCase.getPriority());
  }

  private static String normalizeStatusFilter(String estado) {
    if (estado == null || estado.isBlank()) {
      return null;
    }
    return switch (estado.trim().toLowerCase(Locale.ROOT)) {
      case "en trámite", "en tramite", "draft" -> "DRAFT";
      case "cerrado", "closed" -> "CLOSED";
      default -> estado.trim().toUpperCase(Locale.ROOT);
    };
  }

  private CaseSummaryItem toSummary(LegalCase legalCase) {
    Responsible responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    return new CaseSummaryItem(
        legalCase.getId(),
        legalCase.getCode(),
        legalCase.getVertical(),
        legalCase.getSubject(),
        mapStatusLabel(legalCase.getStatus()),
        responsible.name(),
        responsible.initials(),
        mapPriorityLabel(legalCase.getPriority()),
        formatSla(legalCase.getSlaDueAt()),
        GRID_DATE.format(legalCase.getCreatedAt()),
        GRID_DATE.format(legalCase.getUpdatedAt()),
        resolveCurrentStageLabel(legalCase, legalCase.getTenantId()),
        false);
  }

  private CaseDetailItem toDetail(LegalCase legalCase) {
    UUID tenantId = legalCase.getTenantId();
    CaseParty client =
        caseParties
            .findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
                legalCase.getId(), tenantId, "CLIENT")
            .orElse(null);
    String clientName = client != null ? client.getDisplayName() : null;
    String identification = client != null ? client.getIdentification() : null;
    return toDetail(
        legalCase, clientName, identification, resolveCurrentStageLabel(legalCase, tenantId), null);
  }

  private CaseDetailItem toDetail(LegalCase legalCase, EstadoEscrituracion estado) {
    UUID tenantId = legalCase.getTenantId();
    CaseParty client =
        caseParties
            .findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
                legalCase.getId(), tenantId, "CLIENT")
            .orElse(null);
    String clientName = client != null ? client.getDisplayName() : null;
    String identification = client != null ? client.getIdentification() : null;
    String stage =
        estado != null && estado.etapa() != null
            ? estado.etapa()
            : resolveCurrentStageLabel(legalCase, tenantId);
    return toDetail(legalCase, clientName, identification, stage, estado);
  }

  private String resolveCurrentStageLabel(LegalCase legalCase, UUID tenantId) {
    if (legalCase.getCurrentStageId() == null) {
      return null;
    }
    return caseStages
        .findByIdAndTenantId(legalCase.getCurrentStageId(), tenantId)
        .flatMap(
            stage ->
                stageDefs
                    .findById(stage.getStageDefId())
                    .map(ProcessStageDef::getLabel))
        .orElse(null);
  }

  private CaseDetailItem toDetail(
      LegalCase legalCase,
      String clientName,
      String identification,
      String stage,
      EstadoEscrituracion estado) {
    Responsible responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    return new CaseDetailItem(
        legalCase.getId(),
        legalCase.getCode(),
        legalCase.getVertical(),
        legalCase.getSubject(),
        legalCase.getCaseType(),
        estado != null ? estado.estado() : mapStatusLabel(legalCase.getStatus()),
        legalCase.getStatus(),
        responsible.name(),
        responsible.initials(),
        mapPriorityLabel(legalCase.getPriority()),
        legalCase.getPriority(),
        formatSla(legalCase.getSlaDueAt()),
        clientName,
        identification,
        stage,
        GRID_DATE.format(legalCase.getCreatedAt()),
        GRID_DATE.format(legalCase.getUpdatedAt()),
        estado == null ? null : estado.escrituracionId(),
        estado == null ? DatosBiessMinuta.empty() : estado.datosBiess(),
        estado != null && estado.hasDraft(),
        estado == null ? 1 : estado.etapaIndex(),
        estado == null ? 2 : estado.wizardStep());
  }

  private Responsible resolveResponsible(UUID membershipId) {
    if (membershipId == null) {
      return new Responsible("—", "—");
    }
    Membership membership = memberships.findById(membershipId).orElse(null);
    if (membership == null) {
      return new Responsible("—", "—");
    }
    AppUser user = users.findById(membership.getUserId()).orElse(null);
    if (user == null) {
      return new Responsible("—", "—");
    }
    return new Responsible(user.getDisplayName(), initials(user.getDisplayName()));
  }

  private String allocateCaseCode(UUID tenantId) {
    String pattern = tenantParameters.getCaseCodePattern(tenantId);
    long base = legalCases.countByTenantIdAndDeletedAtIsNull(tenantId);
    for (int attempt = 0; attempt < 20; attempt++) {
      String code = renderCaseCode(pattern, base + attempt + 1);
      if (!legalCases.existsByTenantIdAndCodeAndDeletedAtIsNull(tenantId, code)) {
        return code;
      }
    }
    throw new AuthException(
        HttpStatus.CONFLICT, "CASE_CODE_CONFLICT", "No se pudo generar un código de expediente único.");
  }

  static String renderCaseCode(String pattern, long sequence) {
    int year = java.time.Year.now().getValue();
    return pattern
        .replace("YYYY", String.valueOf(year))
        .replace("###", String.format("%03d", sequence))
        .replace("##", String.format("%02d", sequence));
  }

  private static String resolveCaseType(String requested, String vertical) {
    if (requested != null && !requested.isBlank()) {
      String normalized = requested.trim().toUpperCase(Locale.ROOT);
      if ("EJD".equals(normalized) || "ECD".equals(normalized)) {
        return normalized;
      }
    }
    return vertical.toLowerCase(Locale.ROOT).contains("coactiv") ? "ECD" : "EJD";
  }

  private static String normalizePriority(String priority) {
    if (priority == null || priority.isBlank()) {
      return "MEDIA";
    }
    return switch (priority.trim().toLowerCase(Locale.ROOT)) {
      case "alta", "high" -> "ALTA";
      case "baja", "low" -> "BAJA";
      default -> "MEDIA";
    };
  }

  private static String mapPriorityLabel(String priority) {
    return switch (priority) {
      case "ALTA" -> "Alta";
      case "BAJA" -> "Baja";
      default -> "Media";
    };
  }

  private static String mapStatusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "En trámite";
      case "CLOSED" -> "Cerrado";
      default -> status;
    };
  }

  private static String formatSla(Instant slaDueAt) {
    if (slaDueAt == null) {
      return "—";
    }
    long hours = ChronoUnit.HOURS.between(Instant.now(), slaDueAt);
    if (hours <= 0) {
      return "Vence hoy";
    }
    if (hours < 48) {
      return hours + " h";
    }
    return (hours / 24) + " días";
  }

  private static String normalizeRequired(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
    }
    return value.trim();
  }

  private static String initials(String name) {
    return java.util.Arrays.stream(name.split("\\s+"))
        .filter(part -> !part.isBlank())
        .limit(2)
        .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
        .reduce("", String::concat);
  }

  private record Responsible(String name, String initials) {}
}
