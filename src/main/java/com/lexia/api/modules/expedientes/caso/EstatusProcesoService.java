package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.notary.NotaryRemision;
import com.lexia.api.modules.notary.NotaryRemisionRepository;
import com.lexia.api.modules.notary.NotaryTenantProfile;
import com.lexia.api.modules.notary.NotaryTenantProfileRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Estatus del proceso: catálogo de etapas por tenant ({@code app.estatus_etapa}, V117) e historial
 * inmutable por expediente ({@code app.estatus_proceso}, V115). Corregir un estatus es registrar uno
 * nuevo.
 */
@Service
public class EstatusProcesoService {

  private static final Logger LOG = LoggerFactory.getLogger(EstatusProcesoService.class);
  static final int MAX_TEXTO = 1000;
  static final ZoneId ZONA = ZoneId.of("America/Guayaquil");
  private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/uuuu");
  private static final String OBJETO = "legal_case";

  /**
   * @param sugerido texto sugerido; con expediente, los marcadores ya resueltos.
   * @param etiquetaMatriz texto de la columna «ETAPA» de la matriz de la entidad.
   */
  public record Etapa(String codigo, String etiqueta, String etiquetaMatriz, int orden, String sugerido) {}

  public record Registro(String etapa, String etiqueta, String texto, Instant creadoEn, String autor) {}

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final EstatusProcesoRepository estatus;
  private final EstatusEtapaRepository etapas;
  private final AppUserRepository users;
  private final AuditEventRepository auditEvents;
  private final NotaryRemisionRepository remisiones;
  private final NotaryTenantProfileRepository notarias;

  public EstatusProcesoService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      EstatusProcesoRepository estatus,
      EstatusEtapaRepository etapas,
      AppUserRepository users,
      AuditEventRepository auditEvents,
      NotaryRemisionRepository remisiones,
      NotaryTenantProfileRepository notarias) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.estatus = estatus;
    this.etapas = etapas;
    this.users = users;
    this.auditEvents = auditEvents;
    this.remisiones = remisiones;
    this.notarias = notarias;
  }

  /** Catálogo activo ordenado; con {@code caseId}, el texto sugerido viene con los marcadores resueltos. */
  @Transactional(readOnly = true)
  public List<Etapa> etapas(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = caseId == null ? null : exigirCaso(caseId, tenantId);
    Map<String, String> marcadores = legalCase == null ? null : marcadores(legalCase, tenantId);
    return etapas.findByTenantIdAndActivoTrueOrderByOrdenAsc(tenantId).stream()
        .map(
            e ->
                new Etapa(
                    e.getCodigo(),
                    e.getNombre(),
                    e.etiquetaParaMatriz(),
                    e.getOrden(),
                    marcadores == null
                        ? e.getTextoSugerido()
                        : resolver(e.getTextoSugerido(), marcadores)))
        .toList();
  }

  /** Historial del expediente, el más reciente primero. */
  @Transactional(readOnly = true)
  public List<Registro> listar(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    List<EstatusProceso> filas = estatus.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId);
    Map<String, String> nombres = nombresDeEtapa(tenantId);
    Map<UUID, String> autores = autores(filas);
    return filas.stream().map(row -> aRegistro(row, nombres, autores)).toList();
  }

  @Transactional
  public Registro registrar(UUID caseId, String codigo, String texto) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    EstatusEtapa etapa =
        StringUtils.hasText(codigo)
            ? etapas
                .findByTenantIdAndCodigoAndActivoTrue(tenantId, codigo.trim().toUpperCase(Locale.ROOT))
                .orElse(null)
            : null;
    if (etapa == null) {
      throw ApiException.badRequest("Elige una etapa del catálogo.");
    }
    if (!StringUtils.hasText(texto)) {
      throw ApiException.badRequest("Escribe el texto del estatus.");
    }
    String cuerpo = texto.trim();
    if (cuerpo.length() > MAX_TEXTO) {
      throw ApiException.badRequest("El estatus admite hasta " + MAX_TEXTO + " caracteres.");
    }
    UUID autor = AuthContext.require().userId();
    EstatusProceso guardado =
        estatus.save(EstatusProceso.crear(tenantId, caseId, autor, etapa.getCodigo(), cuerpo));
    // Sin el texto: puede llevar datos del trámite.
    auditEvents.save(
        AuditEvent.of(tenantId, autor, "ESTATUS_REGISTRADO:" + etapa.getCodigo(), OBJETO, caseId, "OK", null, null));
    LOG.info("Estatus guardado case={} etapa={} chars={}", caseId, etapa.getCodigo(), cuerpo.length());
    return aRegistro(
        guardado, Map.of(etapa.getCodigo(), etapa.getNombre()), autores(List.of(guardado)));
  }

  /** Nombre visible por código, incluidas las etapas inactivas (el historial las conserva). */
  public Map<String, String> nombresDeEtapa(UUID tenantId) {
    Map<String, String> out = new HashMap<>();
    for (EstatusEtapa e : etapas.findByTenantId(tenantId)) {
      out.put(e.getCodigo(), e.getNombre());
    }
    return out;
  }

  /** {fecha} hoy en Guayaquil; {notaria} de la última remisión; {numero_instancia} del expediente. */
  private Map<String, String> marcadores(LegalCase legalCase, UUID tenantId) {
    String notaria =
        remisiones
            .findFirstByFirmTenantIdAndFirmLegalCaseIdOrderByEmitidoEnDesc(tenantId, legalCase.getId())
            .map(NotaryRemision::getNotaryTenantId)
            .flatMap(notarias::findById)
            .map(NotaryTenantProfile::getOfficialName)
            .filter(StringUtils::hasText)
            .orElse("la notaría");
    String numero =
        StringUtils.hasText(legalCase.getNumeroInstancia())
            ? legalCase.getNumeroInstancia()
            : "sin número de instancia";
    return Map.of(
        "fecha", FECHA.format(LocalDate.now(ZONA)),
        "notaria", notaria,
        "numero_instancia", numero);
  }

  static String resolver(String plantilla, Map<String, String> marcadores) {
    String out = plantilla == null ? "" : plantilla;
    for (Map.Entry<String, String> m : marcadores.entrySet()) {
      out = out.replace("{" + m.getKey() + "}", m.getValue());
    }
    return out;
  }

  private Map<UUID, String> autores(List<EstatusProceso> filas) {
    Set<UUID> ids = new HashSet<>();
    filas.stream().map(EstatusProceso::getCreatedBy).filter(java.util.Objects::nonNull).forEach(ids::add);
    Map<UUID, String> out = new HashMap<>();
    for (AppUser user : users.findAllById(ids)) {
      if (StringUtils.hasText(user.getDisplayName())) {
        out.put(user.getId(), user.getDisplayName());
      }
    }
    return out;
  }

  private static Registro aRegistro(
      EstatusProceso row, Map<String, String> nombres, Map<UUID, String> autores) {
    return new Registro(
        row.getEtapa(),
        nombres.getOrDefault(row.getEtapa(), row.getEtapa()),
        row.getTexto(),
        row.getCreatedAt(),
        row.getCreatedBy() == null ? "—" : autores.getOrDefault(row.getCreatedBy(), "—"));
  }

  private LegalCase exigirCaso(UUID caseId, UUID tenantId) {
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    CaseReadScope.assertCanRead(authorization, legalCase.getResponsibleMembershipId());
    return legalCase;
  }
}
