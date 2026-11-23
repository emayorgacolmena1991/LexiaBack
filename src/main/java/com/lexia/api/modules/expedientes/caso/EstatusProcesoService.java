package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Historial de estatus del trámite. La lista de etapas se reemplaza cuando la abogada envíe la suya. */
@Service
public class EstatusProcesoService {

  private static final Logger LOG = LoggerFactory.getLogger(EstatusProcesoService.class);
  private static final int MAX_TEXTO = 2000;

  public record Etapa(String codigo, String etiqueta, String sugerido) {}

  public record Registro(String etapa, String etiqueta, String texto, Instant creadoEn, String autor) {}

  public static final List<Etapa> ETAPAS =
      List.of(
          new Etapa("RECEPCION", "Recepción de documentos", "Se recibieron los documentos y se abrió el expediente."),
          new Etapa("ESTUDIO", "Estudio de títulos", "El estudio de títulos está en revisión."),
          new Etapa("REGULARIZACION", "Regularización", "El expediente está en regularización."),
          new Etapa("MINUTA", "Minuta en elaboración", "La minuta está en elaboración."),
          new Etapa("NOTARIA", "Enviado a notaría", "El expediente se envió a la notaría."),
          new Etapa("LIQUIDACION", "Liquidación de impuestos", "Se está liquidando el impuesto."),
          new Etapa("PAGO", "Pago de impuestos", "El impuesto está en pago."),
          new Etapa("FIRMA", "Firma de matriz", "La matriz está lista para la firma."),
          new Etapa("REGISTRO", "Inscripción en el Registro de la Propiedad", "La escritura está en el Registro de la Propiedad."),
          new Etapa("CIERRE", "Cierre", "El trámite quedó cerrado."));

  private static final Map<String, Etapa> POR_CODIGO = new LinkedHashMap<>();

  static {
    for (Etapa etapa : ETAPAS) {
      POR_CODIGO.put(etapa.codigo(), etapa);
    }
  }

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final EstatusProcesoRepository estatus;
  private final AppUserRepository users;

  public EstatusProcesoService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      EstatusProcesoRepository estatus,
      AppUserRepository users) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.estatus = estatus;
    this.users = users;
  }

  public List<Etapa> etapas() {
    authorization.requirePermission("expedientes:caso:leer");
    return ETAPAS;
  }

  public static Etapa etapa(String codigo) {
    if (!StringUtils.hasText(codigo)) {
      return null;
    }
    return POR_CODIGO.get(codigo.trim().toUpperCase());
  }

  @Transactional(readOnly = true)
  public List<Registro> listar(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    return estatus.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId).stream()
        .map(this::aRegistro)
        .toList();
  }

  @Transactional
  public Registro registrar(UUID caseId, String codigo, String texto) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    Etapa etapa = etapa(codigo);
    if (etapa == null) {
      throw ApiException.badRequest("Etapa de estatus no válida.");
    }
    String cuerpo = StringUtils.hasText(texto) ? texto.trim() : etapa.sugerido();
    if (cuerpo.length() > MAX_TEXTO) {
      throw ApiException.badRequest("El estatus es demasiado largo.");
    }
    EstatusProceso guardado =
        estatus.save(
            EstatusProceso.crear(
                tenantId, caseId, AuthContext.require().userId(), etapa.codigo(), cuerpo));
    LOG.info("Estatus guardado case={} etapa={}", caseId, etapa.codigo());
    return aRegistro(guardado);
  }

  private Registro aRegistro(EstatusProceso row) {
    Etapa etapa = etapa(row.getEtapa());
    String autor = "—";
    if (row.getCreatedBy() != null) {
      autor =
          users
              .findById(row.getCreatedBy())
              .map(user -> user.getDisplayName())
              .filter(StringUtils::hasText)
              .orElse("—");
    }
    return new Registro(
        row.getEtapa(),
        etapa == null ? row.getEtapa() : etapa.etiqueta(),
        row.getTexto(),
        row.getCreatedAt(),
        autor);
  }

  private void exigirCaso(UUID caseId, UUID tenantId) {
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(
            () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
  }
}
