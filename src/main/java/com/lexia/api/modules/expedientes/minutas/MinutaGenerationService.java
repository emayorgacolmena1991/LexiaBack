package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.DatosBiessMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
import com.lexia.api.modules.expedientes.escrituracion.MinutaDraft;
import com.lexia.api.modules.expedientes.escrituracion.MinutaDraftRepository;
import com.lexia.api.modules.expedientes.escrituracion.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.WritingFileRepository;
import com.lexia.api.modules.expedientes.proceso.ProductTemplateRepository;
import com.lexia.api.modules.expedientes.reglas.ValidacionIaService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionMinutaVivienda;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.lexia.api.modules.auth.AuthException;

/**
 * Orquesta: OCR consolidado → LLM (MinutaViviendaData) → poi-tl → archivo + {@link MinutaDraft}.
 * Extensible vía {@link MinutaTemplateCatalog}.
 */
@Service
public class MinutaGenerationService {

  private static final Logger LOG = LoggerFactory.getLogger(MinutaGenerationService.class);

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final WritingFileRepository writingFiles;
  private final MinutaDraftRepository minutaDrafts;
  private final ProductTemplateRepository templates;
  private final OcrSessionCacheService ocrCache;
  private final AnalisisDocumentoService analisis;
  private final MinutaTemplateCatalog catalog;
  private final DocxMinutaRenderer renderer;
  private final DatosBiessStore datosBiess;
  private final ObjectMapper objectMapper;
  private final Path storageDir;

  public MinutaGenerationService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      WritingFileRepository writingFiles,
      MinutaDraftRepository minutaDrafts,
      ProductTemplateRepository templates,
      OcrSessionCacheService ocrCache,
      AnalisisDocumentoService analisis,
      MinutaTemplateCatalog catalog,
      DocxMinutaRenderer renderer,
      DatosBiessStore datosBiess,
      ObjectMapper objectMapper,
      @Value("${lexia.minutas.storage-dir:./data/minutas}") String storageDir) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.writingFiles = writingFiles;
    this.minutaDrafts = minutaDrafts;
    this.templates = templates;
    this.ocrCache = ocrCache;
    this.analisis = analisis;
    this.catalog = catalog;
    this.renderer = renderer;
    this.datosBiess = datosBiess;
    this.objectMapper = objectMapper;
    this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
  }

  @Transactional
  public MinutaItem generar(UUID caseId, CrearMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.CONFLICT,
                        "NO_WRITING_FILE",
                        "Configura el producto BIESS antes de generar minutas."));

    String product =
        StringUtils.hasText(file.getProductCode())
            ? file.getProductCode().trim()
            : legalCase.getProductCode();
    if (!StringUtils.hasText(product)) {
      throw new AuthException(
          HttpStatus.CONFLICT, "NO_PRODUCT", "El expediente no tiene producto BIESS.");
    }

    String kind =
        request == null || !StringUtils.hasText(request.templateKind())
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

    MinutaTemplateDescriptor descriptor = catalog.require(product, kind);
    String ocr = loadOcrConsolidated(legalCase, request == null ? null : request.sessionId());
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para generar minutas.");
    }

    ExtraccionMinutaVivienda extraccion = analisis.extraerMinutaVivienda(ocr);
    if (extraccion == null || "ERROR".equals(extraccion.estado())) {
      throw ApiException.badRequest(
          extraccion != null && StringUtils.hasText(extraccion.motivo())
              ? extraccion.motivo()
              : "No se pudieron extraer datos para la minuta.");
    }

    byte[] docx = renderer.renderVivienda(descriptor, extraccion.data());
    MinutaDraft draft = minutaDrafts.save(MinutaDraft.create(tenantId, file.getId(), product, kind));
    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, extraccion.data());
    draft.markReady(stored.toString());
    minutaDrafts.save(draft);
    datosBiess.guardar(tenantId, caseId, DatosBiessMinuta.from(extraccion.data()));

    LOG.info(
        "Minuta generada case={} product={} kind={} draft={} bytes={}",
        caseId,
        product,
        kind,
        draft.getId(),
        docx.length);

    return toItem(draft);
  }

  @Transactional(readOnly = true)
  public DatosBiessMinutaResponse obtenerDatosBiess(UUID caseId, UUID minutaId) {
    authorization.requirePermission("expedientes:caso:leer");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    MinutaViviendaData data = loadData(draft);
    return new DatosBiessMinutaResponse(
        toItem(draft), data == null ? DatosBiessMinuta.empty() : DatosBiessMinuta.from(data));
  }

  /**
   * Re-renderiza el borrador con los datos BIESS (captura o manuales) sobre los datos ya extraídos
   * al generar la minuta. No vuelve a ejecutar OCR, cotejo ni validación del expediente.
   */
  @Transactional
  public DatosBiessMinutaResponse aplicarDatosBiess(
      UUID caseId, UUID minutaId, DatosBiessMinuta datos) {
    authorization.requirePermission("expedientes:caso:escribir");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    MinutaTemplateDescriptor descriptor =
        catalog.require(draft.getProductCode(), draft.getTemplateKind());
    DatosBiessMinuta biess = datos == null ? DatosBiessMinuta.empty() : datos;

    MinutaViviendaData data = loadData(draft);
    if (data == null) {
      // Borradores generados antes de guardar los datos: se reconstruyen una única vez.
      LOG.info("Minuta {} sin datos persistidos; se re-extraen desde OCR en caché", draft.getId());
      data = extraerDatosLegacy(caseId);
    }

    String montoAnterior = DatosBiessMinuta.from(data).monto();
    data.setMontoPrestamo(biess.monto());
    data.setTasaInteresInicial(biess.tasa());
    data.setPlazoCredito(biess.plazo());
    data.setApoderadoBiess(biess.apoderado());
    if (!montoAnterior.equals(biess.monto())) {
      // Evita un monto en letras que ya no coincide con la cifra; queda como dato pendiente.
      data.setMontoPrestamoLetras("");
    }

    byte[] docx = renderer.renderVivienda(descriptor, data);
    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, data);
    draft.markReady(stored.toString());
    minutaDrafts.save(draft);
    datosBiess.guardar(draft.getTenantId(), caseId, DatosBiessMinuta.from(data));

    LOG.info(
        "Datos BIESS aplicados case={} draft={} kind={} bytes={}",
        caseId,
        draft.getId(),
        draft.getTemplateKind(),
        docx.length);
    return new DatosBiessMinutaResponse(toItem(draft), DatosBiessMinuta.from(data));
  }

  @Transactional(readOnly = true)
  public DownloadedMinuta descargar(UUID caseId, UUID minutaId) {
    authorization.requirePermission("expedientes:caso:leer");
    MinutaDraft draft = requireDraft(caseId, minutaId);

    if (!StringUtils.hasText(draft.getStoragePath())) {
      throw ApiException.badRequest("La minuta aún no tiene archivo generado.");
    }
    Path path = Path.of(draft.getStoragePath());
    if (!Files.isRegularFile(path)) {
      throw ApiException.notFound("Archivo DOCX no encontrado en almacenamiento.");
    }
    try {
      String fileName =
          catalog
              .find(draft.getProductCode(), draft.getTemplateKind())
              .map(MinutaTemplateDescriptor::fileName)
              .orElse("minuta.docx");
      return new DownloadedMinuta(fileName, Files.readAllBytes(path));
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo de la minuta.");
    }
  }

  /** Payload en BD; si el borrador es anterior a V47, cae al JSON en disco. */
  public MinutaViviendaData leerDatos(MinutaDraft draft) {
    return loadData(draft);
  }

  public static MinutaItem toItem(MinutaDraft draft) {
    return new MinutaItem(
        draft.getId(),
        draft.getTemplateKind(),
        draft.getProductCode(),
        draft.getStatus(),
        StringUtils.hasText(draft.getStoragePath()));
  }

  public record DownloadedMinuta(String fileName, byte[] bytes) {}

  private MinutaDraft requireDraft(UUID caseId, UUID minutaId) {
    UUID tenantId = AuthContext.require().tenantId();
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));

    WritingFile file =
        writingFiles
            .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Escrituración no encontrada."));

    return minutaDrafts
        .findById(minutaId)
        .filter(m -> tenantId.equals(m.getTenantId()))
        .filter(m -> file.getId().equals(m.getWritingFileId()))
        .orElseThrow(() -> ApiException.notFound("Minuta no encontrada."));
  }

  private MinutaViviendaData extraerDatosLegacy(UUID caseId) {
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase =
        legalCases
            .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
            .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
    if (!analisis.isConfigured()) {
      throw ApiException.badRequest("Proveedor LLM no configurado para generar minutas.");
    }
    ExtraccionMinutaVivienda extraccion =
        analisis.extraerMinutaVivienda(loadOcrConsolidated(legalCase, null));
    if (extraccion == null || "ERROR".equals(extraccion.estado())) {
      throw ApiException.badRequest(
          "Este borrador no tiene datos guardados. Genera la minuta nuevamente.");
    }
    return extraccion.data();
  }

  private Path dataPath(UUID draftId) {
    return storageDir.resolve(draftId + "_datos.json");
  }

  private void persistData(MinutaDraft draft, MinutaViviendaData data) {
    try {
      draft.setPayload(objectMapper.writeValueAsString(data));
    } catch (JsonProcessingException e) {
      throw ApiException.badRequest("No se pudieron guardar los datos de la minuta.");
    }
  }

  private MinutaViviendaData loadData(MinutaDraft draft) {
    if (draft != null && StringUtils.hasText(draft.getPayload())) {
      try {
        return objectMapper.readValue(draft.getPayload(), MinutaViviendaData.class);
      } catch (IOException e) {
        LOG.warn("Payload de minuta ilegible draft={}: {}", draft.getId(), e.getMessage());
      }
    }
    if (draft == null) {
      return null;
    }
    Path path = dataPath(draft.getId());
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      return objectMapper.readValue(path.toFile(), MinutaViviendaData.class);
    } catch (IOException e) {
      LOG.warn("Datos de minuta ilegibles draft={}: {}", draft.getId(), e.getMessage());
      return null;
    }
  }

  private Path persistDocx(UUID draftId, String fileName, byte[] bytes) {
    try {
      Files.createDirectories(storageDir);
      String safeName =
          (fileName == null || fileName.isBlank() ? "minuta.docx" : fileName)
              .replaceAll("[^a-zA-Z0-9._-]", "_");
      Path target = storageDir.resolve(draftId + "_" + safeName);
      Files.write(target, bytes);
      return target;
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo guardar el DOCX generado.");
    }
  }

  private String loadOcrConsolidated(LegalCase legalCase, String sessionIdHint) {
    List<String> candidates = new ArrayList<>();
    if (StringUtils.hasText(sessionIdHint)) {
      candidates.add(sessionIdHint.trim());
    }
    if (StringUtils.hasText(legalCase.getCode())) {
      candidates.add(legalCase.getCode().trim());
    }
    candidates.add(legalCase.getId().toString());

    for (String id : candidates) {
      String cached = ocrCache.getConsolidated(id);
      List<OcrFileResult> results = ocrCache.listResults(id);
      if (cached == null && (results == null || results.isEmpty())) {
        continue;
      }
      if (!StringUtils.hasText(cached) && results != null && !results.isEmpty()) {
        cached = ValidacionIaService.rebuildConsolidado(results);
      }
      if (StringUtils.hasText(cached)) {
        return cached;
      }
    }
    throw ApiException.badRequest(
        "Sin texto OCR consolidado. Completa el paso 3 antes de generar minutas.");
  }
}
