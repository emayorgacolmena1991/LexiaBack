package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseSummaryItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseSummaryPage;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaPage;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.identity.Membership;
import com.lexia.api.modules.identity.MembershipRepository;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.tenancy.TenantParameterService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import com.lexia.api.common.api.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.EstadoEscrituracion;
import com.lexia.api.modules.expedientes.escrituracion.secuencial.ExpedienteEstadoService;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseService {

  private static final Logger LOG = LoggerFactory.getLogger(CaseService.class);
  /** Fechas del bufete (Excel, nombre del archivo). */
  public static final java.time.ZoneId ZONA_BUFETE = java.time.ZoneId.of("America/Guayaquil");

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
  private final CaseGateRepository caseGates;
  private final EstatusProcesoRepository estatus;
  private final EstatusEtapaRepository etapasEstatus;
  private final ProductoBiessService productos;

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
      ExpedienteEstadoService expedienteEstado,
      CaseGateRepository caseGates,
      EstatusProcesoRepository estatus,
      EstatusEtapaRepository etapasEstatus,
      ProductoBiessService productos) {
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
    this.caseGates = caseGates;
    this.estatus = estatus;
    this.etapasEstatus = etapasEstatus;
    this.productos = productos;
  }

  @Transactional(readOnly = true)
  public EscrituracionBandejaPage bandeja(String search, String estado, int page, int size) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    int safeSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_BANDEJA);
    int safePage = Math.max(page, 0);
    Specification<LegalCase> spec =
        bandejaSpec(tenantId, normalizeStatusFilter(estado), search, null);

    Page<LegalCase> result =
        legalCases.findAll(
            spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
    List<EscrituracionBandejaItem> content = filasBandeja(tenantId, result.getContent());
    return new EscrituracionBandejaPage(
        content, result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
  }

  /** Cada fila hace varias consultas (cliente, etapa, responsable): la página no pasa de esto. */
  static final int MAX_BANDEJA = 200;
  /** Lo que la bandeja puede tener cargado: el Excel exporta esas filas. */
  static final int MAX_EXPORTAR = 1000;

  /** Mismo alcance que la bandeja (tenant, EJD, no borrados, responsable si no lee todo). */
  private Specification<LegalCase> bandejaSpec(
      UUID tenantId, String statusCode, String search, Collection<UUID> ids) {
    String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    return (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(cb.equal(root.get("tenantId"), tenantId));
          predicates.add(cb.isNull(root.get("deletedAt")));
          predicates.add(cb.equal(root.get("caseType"), "EJD"));
          if (!CaseReadScope.canReadAll(authorization)) {
            predicates.add(
                cb.equal(root.get("responsibleMembershipId"), AuthContext.require().membershipId()));
          }
          if (ids != null) {
            predicates.add(root.get("id").in(ids));
          }
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
                    cb.like(cb.lower(cb.coalesce(root.get("numeroInstancia"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("operationTypeCode"), "")), like),
                    cb.exists(clients)));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };
  }

  private List<EscrituracionBandejaItem> filasBandeja(UUID tenantId, List<LegalCase> rows) {
    Set<UUID> pendingGates =
        rows.isEmpty()
            ? Set.of()
            : new HashSet<>(
                caseGates.findCaseIdsWithPendingGates(
                    tenantId, rows.stream().map(LegalCase::getId).toList()));
    Map<UUID, EstatusProceso> ultimos = ultimosEstatus(tenantId, rows);
    Map<String, String> nombresEtapa = new HashMap<>();
    if (!ultimos.isEmpty()) {
      etapasEstatus.findByTenantId(tenantId).forEach(e -> nombresEtapa.put(e.getCodigo(), e.getNombre()));
    }
    return rows.stream()
        .map(
            row ->
                toBandeja(
                    row,
                    tenantId,
                    pendingGates.contains(row.getId()),
                    ultimos.get(row.getId()),
                    nombresEtapa))
        .toList();
  }

  @Transactional(readOnly = true)
  public CaseSummaryPage listCasesPage(
      String search,
      String estado,
      String vertical,
      String responsable,
      String prioridad,
      String desde,
      String hasta,
      String etapa,
      Boolean soloMios,
      int page,
      int size) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    AuthPrincipal principal = AuthContext.require();
    int safeSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), 100);
    int safePage = Math.max(page, 0);
    String statusCode = normalizeStatusFilter(estado);
    String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    String verticalFilter = vertical == null ? "" : vertical.trim();
    String owner = responsable == null ? "" : responsable.trim();
    String priorityCode = normalizePriorityFilter(prioridad);
    Instant createdFrom = parseFilterDateStart(desde);
    Instant createdTo = parseFilterDateEnd(hasta);
    boolean restrictMine = Boolean.TRUE.equals(soloMios) || !CaseReadScope.canReadAll(authorization);

    Specification<LegalCase> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(cb.equal(root.get("tenantId"), tenantId));
          predicates.add(cb.isNull(root.get("deletedAt")));
          if (restrictMine) {
            predicates.add(cb.equal(root.get("responsibleMembershipId"), principal.membershipId()));
          }
          if (statusCode != null) {
            predicates.add(cb.equal(cb.upper(root.get("status")), statusCode));
          }
          if (!verticalFilter.isEmpty()) {
            predicates.add(cb.equal(cb.lower(root.get("vertical")), verticalFilter.toLowerCase(Locale.ROOT)));
          }
          if (priorityCode != null) {
            predicates.add(cb.equal(cb.upper(root.get("priority")), priorityCode));
          }
          if (createdFrom != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
          }
          if (createdTo != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
          }
          if (!owner.isEmpty()) {
            String likeOwner = "%" + owner.toLowerCase(Locale.ROOT) + "%";
            Subquery<UUID> userIds = query.subquery(UUID.class);
            Root<AppUser> userRoot = userIds.from(AppUser.class);
            userIds
                .select(userRoot.get("id"))
                .where(cb.like(cb.lower(userRoot.get("displayName")), likeOwner));
            Subquery<UUID> membershipIds = query.subquery(UUID.class);
            Root<Membership> membershipRoot = membershipIds.from(Membership.class);
            membershipIds
                .select(membershipRoot.get("id"))
                .where(
                    cb.equal(membershipRoot.get("tenantId"), tenantId),
                    cb.isNull(membershipRoot.get("deletedAt")),
                    membershipRoot.get("userId").in(userIds));
            predicates.add(root.get("responsibleMembershipId").in(membershipIds));
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

    String etapaFilter = etapa == null ? "" : etapa.trim();
    if (!etapaFilter.isEmpty()) {
      List<LegalCase> candidates =
          legalCases.findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt"));
      List<CaseSummaryItem> filtered =
          candidates.stream()
              .filter(row -> matchesOperativaEtapaFilter(row, tenantId, etapaFilter))
              .map(this::toSummary)
              .toList();
      int total = filtered.size();
      int from = safePage * safeSize;
      if (from >= total) {
        return CaseSummaryPage.empty(safePage, safeSize);
      }
      int to = Math.min(from + safeSize, total);
      List<CaseSummaryItem> slice = filtered.subList(from, to);
      int totalPages = safeSize <= 0 ? 0 : (int) Math.ceil((double) total / safeSize);
      return new CaseSummaryPage(slice, total, totalPages, safePage, safeSize);
    }

    Page<LegalCase> result =
        legalCases.findAll(
            spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
    List<CaseSummaryItem> content = result.getContent().stream().map(this::toSummary).toList();
    return new CaseSummaryPage(
        content,
        result.getTotalElements(),
        result.getTotalPages(),
        result.getNumber(),
        result.getSize());
  }

  private boolean matchesOperativaEtapaFilter(
      LegalCase legalCase, UUID tenantId, String etapaFilter) {
    String filter = etapaFilter.toLowerCase(Locale.ROOT);
    if ("notaria".equals(filter)) {
      filter = "notary";
    }
    var operativa = expedienteEstado.etapaOperativa(legalCase, tenantId);
    String code = operativa.code() == null ? "" : operativa.code().toLowerCase(Locale.ROOT);
    return code.equals(filter);
  }

  private static String normalizePriorityFilter(String prioridad) {
    if (prioridad == null || prioridad.isBlank()) {
      return null;
    }
    return normalizePriority(prioridad);
  }

  private static Instant parseFilterDateStart(String isoDate) {
    if (isoDate == null || isoDate.isBlank()) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(isoDate.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Instant parseFilterDateEnd(String isoDate) {
    if (isoDate == null || isoDate.isBlank()) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(isoDate.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1);
    } catch (Exception ignored) {
      return null;
    }
  }

  @Transactional(readOnly = true)
  public List<CaseSummaryItem> listCases() {
    authorization.requirePermission("expedientes:caso:leer");
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    List<LegalCase> rows =
        CaseReadScope.canReadAll(authorization)
            ? legalCases.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
            : legalCases.findByTenantIdAndResponsibleMembershipIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                tenantId, principal.membershipId());
    return rows.stream().map(this::toSummary).toList();
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
    CaseReadScope.assertCanRead(authorization, legalCase.getResponsibleMembershipId());
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
    if (request.numeroInstancia() != null && !request.numeroInstancia().isBlank()) {
      created.setNumeroInstancia(request.numeroInstancia().trim());
    }
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

  /**
   * @param total expedientes activos del tenant con ese número (sin el excluido).
   * @param codigos los que el usuario puede ver; el resto solo cuenta.
   */
  public record NumeroRepetido(int total, List<String> codigos) {}

  /** Aviso, no bloqueo: dos expedientes con el mismo número de instancia no se cruzan en la matriz. */
  @Transactional(readOnly = true)
  public NumeroRepetido numeroInstanciaRepetido(String numero, UUID excluir) {
    authorization.requirePermission("expedientes:caso:leer");
    AuthPrincipal principal = AuthContext.require();
    String limpio = numero == null ? "" : numero.trim();
    if (limpio.isEmpty()) {
      return new NumeroRepetido(0, List.of());
    }
    List<LegalCase> otros =
        legalCases.findByTenantIdAndNumeroInstanciaAndDeletedAtIsNull(principal.tenantId(), limpio).stream()
            .filter(c -> !c.getId().equals(excluir))
            .toList();
    boolean todos = CaseReadScope.canReadAll(authorization);
    List<String> visibles =
        otros.stream()
            .filter(c -> todos || Objects.equals(c.getResponsibleMembershipId(), principal.membershipId()))
            .map(LegalCase::getCode)
            .toList();
    return new NumeroRepetido(otros.size(), visibles);
  }

  @Transactional
  public CaseDetailItem actualizarNumeroInstancia(UUID caseId, String numero) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = resolveCase(tenantId, caseId.toString());
    String limpio = numero == null ? "" : numero.trim();
    if (limpio.length() > 80) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "El número de instancia es demasiado largo.");
    }
    legalCase.setNumeroInstancia(limpio.isEmpty() ? null : limpio);
    return getCase(caseId);
  }

  /**
   * Excel de las filas que la bandeja tiene filtradas en pantalla. El cliente manda los ids (los
   * filtros de la grilla viven en el front); aquí se vuelven a acotar al tenant y al alcance de la
   * bandeja, así que un id ajeno simplemente no sale.
   */
  @Transactional(readOnly = true)
  public byte[] exportarBandeja(List<UUID> ids) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    List<UUID> pedidos = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    if (pedidos.size() > MAX_EXPORTAR) {
      throw ApiException.badRequest("El Excel admite hasta " + MAX_EXPORTAR + " expedientes; acota los filtros.");
    }
    List<LegalCase> rows =
        pedidos.isEmpty() ? List.of() : legalCases.findAll(bandejaSpec(tenantId, null, null, pedidos));
    Map<UUID, Integer> orden = new HashMap<>();
    for (int i = 0; i < pedidos.size(); i++) {
      orden.put(pedidos.get(i), i);
    }
    List<LegalCase> ordenadas =
        rows.stream().sorted(java.util.Comparator.comparingInt(r -> orden.get(r.getId()))).toList();
    Map<String, String> nombresProducto = ordenadas.isEmpty() ? Map.of() : productos.etiquetas(tenantId);
    List<BandejaExcel.Fila> filas = new ArrayList<>();
    for (EscrituracionBandejaItem item : filasBandeja(tenantId, ordenadas)) {
      String cliente = item.clienteOperacion() == null ? "" : item.clienteOperacion().split("·")[0].trim();
      String producto = item.producto() == null ? "" : nombresProducto.getOrDefault(item.producto(), item.producto());
      filas.add(
          new BandejaExcel.Fila(
              item.codigo(),
              item.numeroInstancia(),
              cliente,
              producto,
              item.etapa(),
              item.ultimoEstatusEtapa(),
              item.ultimoEstatus(),
              item.ultimoEstatusFecha() == null
                  ? null
                  : java.time.LocalDateTime.ofInstant(item.ultimoEstatusFecha(), ZONA_BUFETE),
              item.responsable()));
    }
    LOG.info("Export bandeja escrituracion pedidos={} filas={}", pedidos.size(), filas.size());
    return BandejaExcel.escribir(filas);
  }

  private EscrituracionBandejaItem toBandeja(
      LegalCase legalCase,
      UUID tenantId,
      boolean tieneGatesPendientes,
      EstatusProceso ultimo,
      Map<String, String> nombresEtapa) {
    CaseParty client =
        caseParties
            .findFirstByCaseIdAndTenantIdAndKindAndDeletedAtIsNull(
                legalCase.getId(), tenantId, "CLIENT")
            .orElse(null);
    String cliente = client != null ? client.getDisplayName() : legalCase.getSubject();
    String operacion = legalCase.getOperationTypeCode();
    String clienteOperacion =
        operacion == null || operacion.isBlank() ? cliente : cliente + " · " + operacion.trim();
    String etapa = expedienteEstado.etiquetaEtapaOperativa(legalCase, tenantId);
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
        legalCase.getSlaDueAt() == null ? "—" : GRID_DATE.format(legalCase.getSlaDueAt()),
        legalCase.getCreatedAt() == null ? "" : GRID_DATE.format(legalCase.getCreatedAt()),
        tieneGatesPendientes,
        legalCase.getNumeroInstancia(),
        legalCase.getProductCode(),
        ultimo == null ? "" : ultimo.getTexto(),
        ultimo == null ? "" : GRID_DATE.format(ultimo.getCreatedAt()),
        ultimo == null ? "" : ultimo.getEtapa(),
        ultimo == null ? "" : nombresEtapa.getOrDefault(ultimo.getEtapa(), ultimo.getEtapa()),
        ultimo == null ? null : ultimo.getCreatedAt());
  }

  private Map<UUID, EstatusProceso> ultimosEstatus(UUID tenantId, List<LegalCase> rows) {
    Map<UUID, EstatusProceso> out = new HashMap<>();
    if (rows.isEmpty()) {
      return out;
    }
    for (EstatusProceso row :
        estatus.findRecientes(tenantId, rows.stream().map(LegalCase::getId).toList())) {
      out.putIfAbsent(row.getCaseId(), row);
    }
    return out;
  }

  private static String atencion(LegalCase legalCase) {
    Instant due = legalCase.getSlaDueAt();
    if (due != null && !due.isAfter(Instant.now())) {
      return "Vencida";
    }
    return mapPriorityLabel(legalCase.getPriority());
  }

  static String normalizeStatusFilter(String estado) {
    if (estado == null || estado.isBlank()) {
      return null;
    }
    return switch (estado.trim().toLowerCase(Locale.ROOT)) {
      case "en trámite", "en tramite", "draft" -> "DRAFT";
      case "en revisión", "en revision" -> "IN_REVIEW";
      case "con observación", "con observacion" -> "WITH_OBSERVATION";
      case "pendiente" -> "PENDING";
      case "con excepción", "con excepcion" -> "EXCEPTION";
      case "cerrado", "resuelto", "closed" -> "CLOSED";
      default -> estado.trim().toUpperCase(Locale.ROOT);
    };
  }

  private CaseSummaryItem toSummary(LegalCase legalCase) {
    Responsible responsible = resolveResponsible(legalCase.getResponsibleMembershipId());
    var etapaOperativa = expedienteEstado.etapaOperativa(legalCase, legalCase.getTenantId());
    Instant completedAt = legalCase.getCompletedAt();
    String outcome = legalCase.getCompletionOutcome();
    Instant end = completedAt != null ? completedAt : Instant.now();
    long cycleSeconds = ChronoUnit.SECONDS.between(legalCase.getCreatedAt(), end);
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
        etapaOperativa.label(),
        etapaOperativa.code(),
        outcome,
        mapCompletionOutcomeLabel(outcome),
        completedAt == null ? null : GRID_DATE.format(completedAt),
        cycleSeconds,
        false);
  }

  private static String mapCompletionOutcomeLabel(String outcome) {
    if (outcome == null || outcome.isBlank()) {
      return null;
    }
    return switch (outcome) {
      case "NOTARY_APPROVED" -> "Aprobado en notaría";
      case "COACTIVE_ARCHIVED" -> "Archivado (coactiva)";
      default -> outcome;
    };
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
        estado == null ? 2 : estado.wizardStep(),
        legalCase.getNumeroInstancia());
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

  /** Inverso de normalizeStatusFilter: el frontend vuelve a filtrar las filas por esta etiqueta. */
  static String mapStatusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "En trámite";
      case "IN_REVIEW" -> "En revisión";
      case "WITH_OBSERVATION" -> "Con observación";
      case "PENDING" -> "Pendiente";
      case "EXCEPTION" -> "Con excepción";
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
