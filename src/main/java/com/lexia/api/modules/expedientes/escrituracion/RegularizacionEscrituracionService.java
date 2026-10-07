package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.RegularizacionResponse;
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

  public RegularizacionEscrituracionService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
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
      List<String> abiertas = observacionesAbiertas(file, tenantId);
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
    TitleObservation obs =
        titleObservations
            .findByIdAndTenantId(observacionId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Observación no encontrada."));
    TitleStudy study =
        titleStudies
            .findById(obs.getTitleStudyId())
            .filter(s -> file.getId().equals(s.getWritingFileId()))
            .orElseThrow(() -> ApiException.notFound("Observación no encontrada."));
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
    List<String> abiertas = observacionesAbiertas(file, tenantId);
    boolean puede = file.isFlagBloqueoReenvio() && abiertas.isEmpty() && !productoVacio(legalCase, file);
    return new RegularizacionResponse(
        legalCase.getId(),
        file.getId(),
        file.getEstadoRegularizacion(),
        file.isFlagBloqueoReenvio(),
        puede,
        abiertas);
  }

  private List<String> observacionesAbiertas(WritingFile file, UUID tenantId) {
    return titleStudies
        .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(file.getId(), tenantId)
        .map(
            study ->
                titleObservations.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(study.getId(), tenantId).stream()
                    .filter(o -> !"RESOLVED".equals(o.getStatus()))
                    .map(TitleObservation::getDetail)
                    .toList())
        .orElse(List.of());
  }

  private static boolean productoVacio(LegalCase legalCase, WritingFile file) {
    String code = file.getProductCode() != null ? file.getProductCode() : legalCase.getProductCode();
    return code == null || code.isBlank();
  }
}
