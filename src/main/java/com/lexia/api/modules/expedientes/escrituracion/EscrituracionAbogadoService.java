package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ConfigurarProductoRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.WritingSnapshot;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.ejd.EjdValidationEvaluationService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.expedientes.proceso.ProductTemplateRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class EscrituracionAbogadoService {

  private static final List<String> INGESTION_MODES =
      List.of("DIGITAL_SEPARADO", "FISICO_ESCANEADO");

  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final TitleStudyRepository titleStudies;
  private final TitleObservationRepository titleObservations;
  private final MinutaDraftRepository minutaDrafts;
  private final ProductTemplateRepository templates;
  private final ProductoBiessService productos;
  private final AuthorizationService authorization;
  private final EjdValidationEvaluationService validationEvaluation;

  public EscrituracionAbogadoService(
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      TitleStudyRepository titleStudies,
      TitleObservationRepository titleObservations,
      MinutaDraftRepository minutaDrafts,
      ProductTemplateRepository templates,
      ProductoBiessService productos,
      AuthorizationService authorization,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          EjdValidationEvaluationService validationEvaluation) {
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.titleStudies = titleStudies;
    this.titleObservations = titleObservations;
    this.minutaDrafts = minutaDrafts;
    this.templates = templates;
    this.productos = productos;
    this.authorization = authorization;
    this.validationEvaluation = validationEvaluation;
  }

  @Transactional(readOnly = true)
  public WritingSnapshot snapshot(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireCase(caseId, tenantId);
    WritingFile file = writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    if (file == null) {
      return new WritingSnapshot(
          null,
          legalCase.getProductCode(),
          null,
          legalCase.getIngestionMode(),
          null,
          List.of());
    }
    return toSnapshot(file, tenantId);
  }

  @Transactional
  public WritingSnapshot configurarProducto(UUID caseId, ConfigurarProductoRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireCase(caseId, tenantId);
    if (!"EJD".equals(legalCase.getCaseType())) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "NOT_EJD", "Solo expedientes EJD admiten producto BIESS.");
    }
    var producto = productos.requireProducto(tenantId, request.productCode());
    String canton = ProductoBiessService.normalizeCanton(request.canton());
    String ingestion = normalizeIngestion(request.ingestionMode());

    legalCase.setProductCode(producto.getCode());
    legalCase.setIngestionMode(ingestion);
    if (request.operationTypeCode() != null && !request.operationTypeCode().isBlank()) {
      legalCase.setOperationTypeCode(request.operationTypeCode().trim().toUpperCase(Locale.ROOT));
    } else if (legalCase.getOperationTypeCode() == null) {
      legalCase.setOperationTypeCode(defaultOperation(producto.getCode()));
    }
    legalCases.save(legalCase);

    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseGet(() -> writingFiles.save(WritingFile.create(tenantId, caseId)));
    file.applyProduct(producto.getCode(), canton, ingestion);
    writingFiles.save(file);

    if (validationEvaluation != null) {
      validationEvaluation.refreshForCase(legalCase);
    }
    return toSnapshot(file, tenantId);
  }

  @Transactional
  public EstudioTituloResponse registrarEstudio(UUID caseId, EstudioTituloRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    requireCase(caseId, tenantId);
    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.CONFLICT,
                        "NO_WRITING_FILE",
                        "Configura el producto BIESS antes del estudio de título."));

    String status =
        request.status() == null || request.status().isBlank()
            ? "PENDING"
            : request.status().trim().toUpperCase(Locale.ROOT);
    TitleStudy study =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(file.getId(), tenantId)
            .orElseGet(() -> titleStudies.save(TitleStudy.create(tenantId, file.getId())));
    study.applyResult(status, request.summary());
    titleStudies.save(study);

    if (request.observaciones() != null) {
      for (String obs : request.observaciones()) {
        if (obs != null && !obs.isBlank()) {
          titleObservations.save(TitleObservation.create(tenantId, study.getId(), obs.trim()));
        }
      }
    }
    return toEstudio(study, tenantId);
  }

  @Transactional
  public MinutaItem crearMinuta(UUID caseId, CrearMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireCase(caseId, tenantId);
    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.CONFLICT,
                        "NO_WRITING_FILE",
                        "Configura el producto BIESS antes de generar minutas."));
    String product = file.getProductCode() != null ? file.getProductCode() : legalCase.getProductCode();
    if (product == null) {
      throw new AuthException(
          HttpStatus.CONFLICT, "NO_PRODUCT", "El expediente no tiene producto BIESS.");
    }
    String kind =
        request.templateKind() == null || request.templateKind().isBlank()
            ? "MINUTA_COMPRAVENTA"
            : request.templateKind().trim().toUpperCase(Locale.ROOT);

    boolean allowed =
        templates
            .findByTenantIdAndProductCodeAndActiveTrueOrderBySortOrderAsc(tenantId, product)
            .stream()
            .anyMatch(t -> kind.equals(t.getTemplateKind()) && !t.isCompanySuppliesCv());
    if (!allowed) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "TEMPLATE_NOT_ALLOWED",
          "Plantilla no disponible para este producto (o la suministra la compañía): " + kind);
    }

    MinutaDraft draft = minutaDrafts.save(MinutaDraft.create(tenantId, file.getId(), product, kind));
    return new MinutaItem(draft.getId(), draft.getTemplateKind(), draft.getProductCode(), draft.getStatus());
  }

  private WritingSnapshot toSnapshot(WritingFile file, UUID tenantId) {
    TitleStudy study =
        titleStudies
            .findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(file.getId(), tenantId)
            .orElse(null);
    List<MinutaItem> minutas =
        minutaDrafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(file.getId(), tenantId).stream()
            .map(
                m ->
                    new MinutaItem(
                        m.getId(), m.getTemplateKind(), m.getProductCode(), m.getStatus()))
            .toList();
    return new WritingSnapshot(
        file.getId(),
        file.getProductCode(),
        file.getCanton(),
        file.getIngestionMode(),
        study == null ? null : toEstudio(study, tenantId),
        minutas);
  }

  private EstudioTituloResponse toEstudio(TitleStudy study, UUID tenantId) {
    List<String> open =
        titleObservations
            .findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(study.getId(), tenantId)
            .stream()
            .filter(o -> "OPEN".equals(o.getStatus()))
            .map(TitleObservation::getDetail)
            .toList();
    return new EstudioTituloResponse(study.getId(), study.getStatus(), study.getSummary(), open);
  }

  private LegalCase requireCase(UUID caseId, UUID tenantId) {
    return legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(
            () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
  }

  private static String normalizeIngestion(String raw) {
    if (raw == null || raw.isBlank()) {
      return "DIGITAL_SEPARADO";
    }
    String mode = raw.trim().toUpperCase(Locale.ROOT);
    if ("FISICO_ESCANEDO".equals(mode)) {
      mode = "FISICO_ESCANEADO";
    }
    if (!INGESTION_MODES.contains(mode)) {
      throw ApiException.badRequest("Modo de ingesta inválido: " + raw);
    }
    return mode;
  }

  private static String defaultOperation(String productCode) {
    if ("SUSTITUCION_HIPOTECA".equals(productCode)) {
      return "HIPOTECA";
    }
    return "COMPRAVENTA";
  }
}
