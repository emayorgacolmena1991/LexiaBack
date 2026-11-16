package com.lexia.api.modules.expedientes.escrituracion.secuencial;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.RegularizacionResponse;
import com.lexia.api.modules.expedientes.escrituracion.core.ObservacionesCotejo;
import com.lexia.api.modules.expedientes.escrituracion.core.ObservacionesPorDocumento;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservation;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservationRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudy;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PROCESO_REGULAR → EN_REGULARIZACION → CORREGIDO_POR_USUARIO → PROCESO_REGULAR.
 * Con el flag activo el reanálisis IA no vuelve a dispararse.
 */
@Service
public class RegularizacionEscrituracionService {

  static final String PROCESO_REGULAR = "PROCESO_REGULAR";
  static final String EN_REGULARIZACION = "EN_REGULARIZACION";

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final ObservacionesPorDocumento porDocumento;

  public RegularizacionEscrituracionService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      ObservacionesPorDocumento porDocumento) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.porDocumento = porDocumento;
  }

  @Transactional
  public RegularizacionResponse enviar(UUID caseId) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireEjd(caseId, tenantId);
    WritingFile file = asegurar(tenantId, legalCase.getId());
    if (PROCESO_REGULAR.equals(file.getEstadoRegularizacion())) {
      file.enviarRegularizacion();
    }
    return respuesta(legalCase, file, tenantId);
  }

  @Transactional
  public RegularizacionResponse marcarCorregido(UUID caseId) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireEjd(caseId, tenantId);
    WritingFile file = requireFile(tenantId, legalCase.getId());
    if (PROCESO_REGULAR.equals(file.getEstadoRegularizacion())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "REGULARIZACION_NO_INICIADA",
          "El expediente no está en regularización.");
    }
    if (EN_REGULARIZACION.equals(file.getEstadoRegularizacion())) {
      file.marcarCorregido();
    }
    return respuesta(legalCase, file, tenantId);
  }

  @Transactional
  public RegularizacionResponse marcarRegularizado(UUID caseId) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireEjd(caseId, tenantId);
    WritingFile file = requireFile(tenantId, legalCase.getId());
    if (file.isFlagBloqueoReenvio() || !PROCESO_REGULAR.equals(file.getEstadoRegularizacion())) {
      List<ObservacionCotejoItem> abiertas = observacionesAbiertas(file, tenantId);
      if (productoVacio(legalCase, file) || !abiertas.isEmpty()) {
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "REGULARIZACION_INCOMPLETA",
            "Completa el producto y cierra las observaciones abiertas antes de salir de regularización.",
            List.of(),
            java.util.Map.of("observacionesAbiertas", abiertas));
      }
      file.marcarRegularizado();
    }
    return respuesta(legalCase, file, tenantId);
  }

  @Transactional
  public RegularizacionResponse resolverObservacion(UUID caseId, UUID observacionId) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireEjd(caseId, tenantId);
    WritingFile file = requireFile(tenantId, legalCase.getId());
    // Idempotente: tras reanálisis IA las obs abiertas se recrean con otro UUID; cerrar un id
    // fantasma no debe 404 — devolvemos el estado actual (lista ya limpia).
    TitleObservation obs =
        titleObservations.findByIdAndTenantId(observacionId, tenantId).orElse(null);
    if (obs == null) {
      return respuesta(legalCase, file, tenantId);
    }
    TitleStudy study =
        titleStudies
            .findById(obs.getTitleStudyId())
            .filter(s -> file.getId().equals(s.getWritingFileId()))
            .orElse(null);
    if (study == null) {
      return respuesta(legalCase, file, tenantId);
    }
    if (!"RESOLVED".equals(obs.getStatus())) {
      obs.resolve();
    }
    if ("PENDING".equals(study.getStatus()) && observacionesAbiertas(file, tenantId).isEmpty()) {
      study.applyResult("REVIEWED", study.getSummary());
    }
    return respuesta(legalCase, file, tenantId);
  }

  private WritingFile asegurar(UUID tenantId, UUID caseId) {
    return writingFiles
        .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseGet(() -> writingFiles.save(WritingFile.create(tenantId, caseId)));
  }

  private WritingFile requireFile(UUID tenantId, UUID caseId) {
    return writingFiles
        .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("El expediente no tiene ficha de escrituración."));
  }

  private LegalCase requireEjd(UUID caseId, UUID tenantId) {
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
    if (!"EJD".equals(legalCase.getCaseType())) {
      throw ApiException.badRequest("Solo expedientes de escrituración admiten regularización.");
    }
    return legalCase;
  }

  private RegularizacionResponse respuesta(LegalCase legalCase, WritingFile file, UUID tenantId) {
    List<ObservacionCotejoItem> abiertas = observacionesAbiertas(file, tenantId);
    boolean puede = file.isFlagBloqueoReenvio() && abiertas.isEmpty() && !productoVacio(legalCase, file);
    ObservacionesPorDocumento.Agrupadas agrupadas =
        porDocumento.agrupar(legalCase.getId(), tenantId, abiertas);
    return new RegularizacionResponse(
        legalCase.getId(),
        file.getId(),
        file.getEstadoRegularizacion(),
        file.isFlagBloqueoReenvio(),
        puede,
        agrupadas.abiertas(),
        agrupadas.porDocumento(),
        agrupadas.generales(),
        agrupadas.faltantes());
  }

  private List<ObservacionCotejoItem> observacionesAbiertas(WritingFile file, UUID tenantId) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(file.getId(), tenantId)
        .map(study -> ObservacionesCotejo.pendientes(titleObservations, tenantId, study.getId()))
        .orElse(List.of());
  }

  private static boolean productoVacio(LegalCase legalCase, WritingFile file) {
    String code = file.getProductCode() != null ? file.getProductCode() : legalCase.getProductCode();
    return code == null || code.isBlank();
  }
}
