package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.ExpedienteDtos.CaseSummaryItem;
import com.lexia.api.modules.expedientes.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
      CaseBootstrapService bootstrap) {
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
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    return toDetail(legalCase);
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
    legalCases.save(created);
    return toDetail(created);
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
    return toDetail(legalCase, clientName, identification, resolveCurrentStageLabel(legalCase, tenantId));
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
      LegalCase legalCase, String clientName, String identification, String stage) {
    Responsible responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    return new CaseDetailItem(
        legalCase.getId(),
        legalCase.getCode(),
        legalCase.getVertical(),
        legalCase.getSubject(),
        legalCase.getCaseType(),
        mapStatusLabel(legalCase.getStatus()),
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
        GRID_DATE.format(legalCase.getUpdatedAt()));
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
