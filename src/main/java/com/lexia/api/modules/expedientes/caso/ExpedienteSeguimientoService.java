package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ActuacionItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.AuditoriaItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ExcepcionItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ValidacionItem;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Documentos, validaciones de IA, actuaciones y auditoría del expediente oficial. */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ExpedienteSeguimientoService {

  private static final String KIND_IA = "Analisis IA";
  private static final String OBJETO = "legal_case";

  private final LegalCaseRepository legalCases;
  private final CaseDocumentoStore caseDocs;
  private final CargaDocumentoService carga;
  private final CaseValidationRepository validations;
  private final CaseExceptionRepository exceptions;
  private final CaseActionRepository actions;
  private final AuditEventRepository auditEvents;
  private final AppUserRepository users;
  private final AuthorizationService authorization;

  public ExpedienteSeguimientoService(
      LegalCaseRepository legalCases,
      CaseDocumentoStore caseDocs,
      CargaDocumentoService carga,
      CaseValidationRepository validations,
      CaseExceptionRepository exceptions,
      CaseActionRepository actions,
      AuditEventRepository auditEvents,
      AppUserRepository users,
      AuthorizationService authorization) {
    this.legalCases = legalCases;
    this.caseDocs = caseDocs;
    this.carga = carga;
    this.validations = validations;
    this.exceptions = exceptions;
    this.actions = actions;
    this.auditEvents = auditEvents;
    this.users = users;
    this.authorization = authorization;
  }

  /** Copia archivos y OCR del borrador al expediente recién creado. */
  @Transactional
  public void vincularBorrador(String draftId, UUID caseId) {
    CaseFileContext ctx = caseDocs.context(caseId.toString());
    if (ctx == null || !StringUtils.hasText(draftId)) {
      return;
    }
    Map<String, String> ids = new LinkedHashMap<>();
    for (StoredDoc doc : docsDelBorrador(draftId)) {
      if (doc.bytes() == null || doc.bytes().length == 0) {
        continue;
      }
      DocumentoCargadoDTO saved =
          caseDocs.subir(ctx, doc.nombreOriginal(), doc.mimeType(), doc.bytes());
      if (StringUtils.hasText(doc.codigoTipoDocumento())) {
        caseDocs.actualizarTipo(ctx, saved.idDocumento(), doc.codigoTipoDocumento());
      }
      ids.put(doc.idDocumento(), saved.idDocumento());
    }
    for (DocumentoOcrResultadoDTO row : ocrDelBorrador(draftId)) {
      String nuevo = ids.getOrDefault(row.idDocumento(), row.idDocumento());
      caseDocs.guardarOcr(
          ctx,
          new DocumentoOcrResultadoDTO(
              nuevo,
              row.nombreOriginal(),
              row.tipoDocumento(),
              row.textoOcr(),
              row.analisisJson(),
              row.estado(),
              row.motivo(),
              row.confianza()));
    }
    AuthPrincipal auth = AuthContext.get();
    sincronizarAnalisis(caseId, ctx.tenantId(), auth == null ? null : auth.userId());
  }

  @Transactional
  public void documentoSubido(UUID caseId, UUID tenantId, String nombre) {
    String titulo = "Carga de archivos" + (StringUtils.hasText(nombre) ? ": " + cortar(nombre, 180) : "");
    AuthPrincipal auth = AuthContext.get();
    UUID userId = auth == null ? null : auth.userId();
    actions.save(
        CaseAction.create(
            tenantId, caseId, titulo, "DOCUMENTO_SUBIDO", "COMPLETED", userId, Instant.now()));
    auditEvents.save(
        AuditEvent.of(tenantId, userId, "DOCUMENTO_SUBIDO", OBJETO, caseId, "OK", null, null));
  }

  @Transactional
  public void registrarAnalisisSiExpediente(String rawId) {
    CaseFileContext ctx = caseDocs.context(rawId);
    if (ctx == null) {
      return;
    }
    AuthPrincipal auth = AuthContext.get();
    sincronizarAnalisis(ctx.caseId(), ctx.tenantId(), auth == null ? null : auth.userId());
  }

  @Transactional(readOnly = true)
  public List<ValidacionItem> validaciones(UUID caseId) {
    UUID tenantId = requireCase(caseId).tenantId();
    return validations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(caseId, tenantId).stream()
        .map(
            row ->
                new ValidacionItem(
                    row.getId(),
                    row.getLabel(),
                    row.getKind(),
                    row.getResult(),
                    row.getEvidence(),
                    row.getCreatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ExcepcionItem> excepciones(UUID caseId) {
    UUID tenantId = requireCase(caseId).tenantId();
    return exceptions.findByCaseIdAndTenantIdOrderByCreatedAtDesc(caseId, tenantId).stream()
        .map(
            row ->
                new ExcepcionItem(
                    row.getId(),
                    row.getTitle(),
                    row.getSeverity(),
                    row.getStatus(),
                    row.getDetail(),
                    row.getEvidence(),
                    row.getCreatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ActuacionItem> actuaciones(UUID caseId) {
    UUID tenantId = requireCase(caseId).tenantId();
    return actions.findByCaseIdAndTenantIdOrderByCreatedAtDesc(caseId, tenantId).stream()
        .map(
            row ->
                new ActuacionItem(
                    row.getId(),
                    row.getTitle(),
                    row.getActionType(),
                    row.getStatus(),
                    actor(row.getActorUserId()),
                    row.getActedAt() == null ? row.getCreatedAt() : row.getActedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AuditoriaItem> auditoria(UUID caseId) {
    UUID tenantId = requireCase(caseId).tenantId();
    return auditEvents
        .findByTenantIdAndObjectTypeAndObjectIdOrderByCreatedAtDesc(tenantId, OBJETO, caseId)
        .stream()
        .map(
            row ->
                new AuditoriaItem(
                    row.getId(),
                    row.getEvent(),
                    actor(row.getActorUserId()),
                    row.getResult(),
                    row.getCreatedAt()))
        .toList();
  }

  private void sincronizarAnalisis(UUID caseId, UUID tenantId, UUID userId) {
    CaseFileContext ctx = caseDocs.context(caseId.toString());
    if (ctx == null) {
      return;
    }
    List<DocumentoOcrResultadoDTO> ocr = caseDocs.listarOcr(ctx);
    if (ocr.isEmpty()) {
      return;
    }
    validations
        .findByCaseIdAndTenantIdOrderByCreatedAtAsc(caseId, tenantId)
        .stream()
        .filter(row -> KIND_IA.equals(row.getKind()))
        .forEach(validations::delete);
    for (DocumentoOcrResultadoDTO row : ocr) {
      String result = resultadoIa(row.estado());
      String label = cortar(texto(row.tipoDocumento(), row.nombreOriginal(), "Documento"), 200);
      String evidence = cortar(texto(row.motivo(), row.analisisJson(), row.estado()), 2000);
      CaseValidation validation =
          CaseValidation.create(tenantId, caseId, null, label, KIND_IA, result, "ocr");
      validation.applyEvaluation(result, evidence, "ocr");
      validations.save(validation);
      if ("FAIL".equals(result)) {
        abrirExcepcion(tenantId, caseId, label, evidence);
      }
    }
    if (!actions.existsByCaseIdAndTenantIdAndActionType(caseId, tenantId, "IA_ANALISIS_COMPLETADO")) {
      actions.save(
          CaseAction.create(
              tenantId,
              caseId,
              "Análisis IA",
              "IA_ANALISIS_COMPLETADO",
              "COMPLETED",
              userId,
              Instant.now()));
      auditEvents.save(
          AuditEvent.of(
              tenantId, userId, "IA_ANALISIS_COMPLETADO", OBJETO, caseId, "OK", null, null));
    }
  }

  private void abrirExcepcion(UUID tenantId, UUID caseId, String title, String evidence) {
    if (exceptions
        .findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(
            caseId, tenantId, "VALIDATION_FAIL", title, "OPEN")
        .isPresent()) {
      return;
    }
    AuthPrincipal auth = AuthContext.get();
    exceptions.save(
        CaseException.openFromValidation(
            tenantId,
            caseId,
            auth == null ? null : auth.membershipId(),
            title,
            evidence,
            "ALTA"));
  }

  private List<StoredDoc> docsDelBorrador(String draftId) {
    try {
      return new ArrayList<>(carga.documentosParaProcesar(draftId));
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private List<DocumentoOcrResultadoDTO> ocrDelBorrador(String draftId) {
    try {
      return carga.listarOcrResultados(draftId);
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private CaseFileContext requireCase(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    AuthPrincipal auth = AuthContext.require();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, auth.tenantId())
        .orElseThrow(
            () -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expediente no encontrado."));
    CaseFileContext ctx = caseDocs.context(caseId.toString());
    if (ctx == null) {
      throw ApiException.notFound("Expediente no encontrado.");
    }
    return ctx;
  }

  private String actor(UUID userId) {
    if (userId == null) {
      return "Sistema";
    }
    return users.findById(userId).map(AppUser::getDisplayName).orElse("Sistema");
  }

  private static String resultadoIa(String estado) {
    if (estado == null) {
      return "REVIEW_REQUIRED";
    }
    return switch (estado.trim().toUpperCase()) {
      case "LEGIBLE", "PASS" -> "PASS";
      case "ERROR", "ILEGIBLE", "FAIL" -> "FAIL";
      default -> "REVIEW_REQUIRED";
    };
  }

  private static String texto(String first, String second, String fallback) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    if (StringUtils.hasText(second)) {
      return second.trim();
    }
    return fallback;
  }

  private static String cortar(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
