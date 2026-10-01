package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.BorradorGeneradoResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ContenidoMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.DatosBiessMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.GuardarContenidoMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.GuardarMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaGuardadaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ParrafoEditado;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.PreviewMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.VariablesMinutaResponse;
import com.lexia.api.modules.expedientes.minutas.ExpedienteVariablesService.VariablesConsolidadas;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
  private final ExtractedDataRepository extractedData;
  private final ObjectMapper objectMapper;
  private final Path storageDir;
  private final BorradorPromocionService promocion;
  private final MinutaDocxContenido contenido;
  private final ExpedienteVariablesService variablesService;
  private final DocxPdfConverter pdf;

  /**
   * Campos canónicos que cambia la captura BIESS / ingreso manual; lo único que se toca en un DOCX
   * editado (junto con sus tags alias en la plantilla).
   */
  static final List<String> TAGS_BIESS =
      List.of(
          "monto_prestamo",
          "monto_prestamo_letras",
          "tasa_interes_inicial",
          "plazo_credito",
          "cuota_credito",
          "apoderado_biess");

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
      ExtractedDataRepository extractedData,
      ObjectMapper objectMapper,
      @Value("${lexia.minutas.storage-dir:./data/minutas}") String storageDir,
      BorradorPromocionService promocion,
      MinutaDocxContenido contenido,
      ExpedienteVariablesService variablesService,
      DocxPdfConverter pdf) {
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
    this.extractedData = extractedData;
    this.objectMapper = objectMapper;
    this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    this.promocion = promocion;
    this.contenido = contenido;
    this.variablesService = variablesService;
    this.pdf = pdf;
  }

  // --- TICKET-INT-102: flujo data-driven (variables JSON → poi-tl → PDF de previsualización) ---

  /**
   * JSON unificado del acto para el panel de variables. Solo lectura: con borrador usa su payload +
   * BIESS guardado + overrides; sin borrador, el consolidado del expediente + BIESS (sin LLM).
   */
  @Transactional(readOnly = true)
  public VariablesMinutaResponse variables(UUID caseId, String tipoMinuta) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireCase(caseId, tenantId);
    WritingFile file =
        writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId).orElse(null);
    String product = productoDe(file, legalCase);
    String kind = normalizarKind(tipoMinuta);
    MinutaTemplateDescriptor descriptor = catalog.require(product, kind);
    MinutaDraft draft = file == null ? null : ultimoDraft(file, tenantId, kind);

    Map<String, String> overrides =
        draft == null ? Map.of() : variablesService.leerOverrides(draft.getOverrides());
    MinutaViviendaData base = draft == null ? null : loadData(draft);
    if (base == null) {
      base = new MinutaViviendaData();
      completarDesdeConsolidado(tenantId, caseId, base);
    }
    MinutaViviendaData data =
        variablesService.fusionar(base, datosBiess.cargar(caseId, tenantId), overrides);
    return respuestaVariables(draft, descriptor, variablesService.consolidar(descriptor, data));
  }

  /**
   * Aplica los overrides del panel, persiste el JSON final en el borrador (creándolo si no existe),
   * re-renderiza el .docx con poi-tl y devuelve el PDF de previsualización. El .docx que sirve la
   * descarga es exactamente el que se convirtió a PDF.
   */
  @Transactional
  public PreviewMinuta previsualizar(UUID caseId, String tipoMinuta, PreviewMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    caseId = promocion.asegurarExpediente(caseId);
    UUID tenantId = AuthContext.require().tenantId();
    LegalCase legalCase = requireCase(caseId, tenantId);
    WritingFile file = requireWritingFile(caseId, tenantId);
    String product = productoDe(file, legalCase);
    if (!StringUtils.hasText(product)) {
      throw new AuthException(
          HttpStatus.CONFLICT, "NO_PRODUCT", "El expediente no tiene producto BIESS.");
    }
    String kind = normalizarKind(tipoMinuta);
    MinutaTemplateDescriptor descriptor = catalog.require(product, kind);

    MinutaDraft draft = ultimoDraft(file, tenantId, kind);
    MinutaViviendaData base = draft == null ? null : loadData(draft);
    if (draft == null) {
      base = datosBase(tenantId, caseId, legalCase, null, product, kind);
      draft = minutaDrafts.save(MinutaDraft.create(tenantId, file.getId(), product, kind));
    } else if (base == null) {
      LOG.info("Minuta {} sin datos persistidos; se reconstruyen desde el expediente", draft.getId());
      base = datosBase(tenantId, caseId, legalCase, null, product, kind);
    }

    Map<String, String> overrides = variablesService.leerOverrides(draft.getOverrides());
    overrides.putAll(
        variablesService.normalizarOverrides(descriptor, request == null ? null : request.variables()));
    DatosBiessMinuta biessGuardado = datosBiess.cargar(caseId, tenantId);
    MinutaViviendaData data = variablesService.fusionar(base, biessGuardado, overrides);

    byte[] docx = renderizarDataDriven(draft, descriptor, base, data);
    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, data);
    draft.setOverrides(variablesService.escribirOverrides(overrides));
    draft.markGenerated(stored.toString());
    minutaDrafts.save(draft);
    datosBiess.guardar(tenantId, caseId, biessDesde(data, biessGuardado));

    VariablesConsolidadas consolidadas = variablesService.consolidar(descriptor, data);
    byte[] pdfBytes = pdf.toPdf(docx);
    LOG.info(
        "Preview minuta case={} draft={} kind={} overrides={} pendientes={} pdfBytes={}",
        caseId,
        draft.getId(),
        kind,
        overrides.size(),
        consolidadas.variablesPendientes().size(),
        pdfBytes.length);
    return new PreviewMinuta(
        draft.getId(), pdfBytes, respuestaVariables(draft, descriptor, consolidadas));
  }

  public record PreviewMinuta(UUID minutaId, byte[] pdf, VariablesMinutaResponse variables) {}

  /**
   * Render desde la plantilla con el JSON final; si el DOCX trae ediciones manuales (flujo
   * anterior) solo se parchean los tags cuyo valor cambió para no perder ese texto.
   */
  private byte[] renderizarDataDriven(
      MinutaDraft draft,
      MinutaTemplateDescriptor descriptor,
      MinutaViviendaData antes,
      MinutaViviendaData despues) {
    if (!draft.isEditedManually() || !StringUtils.hasText(draft.getStoragePath())) {
      return renderer.renderVivienda(descriptor, despues);
    }
    Map<String, Object> mapaAntes = antes.toTemplateMap();
    Map<String, Object> mapaDespues = despues.toTemplateMap();
    List<String> cambiados =
        mapaDespues.keySet().stream()
            .filter(c -> !String.valueOf(mapaAntes.get(c)).equals(String.valueOf(mapaDespues.get(c))))
            .toList();
    if (cambiados.isEmpty()) {
      return leerDocx(draft);
    }
    return contenido.actualizarValores(
        leerDocx(draft),
        renderer.plantilla(descriptor),
        renderer.valoresPorTag(descriptor, antes),
        renderer.valoresPorTag(descriptor, despues),
        renderer.tagsDeCampos(descriptor, cambiados));
  }

  private VariablesMinutaResponse respuestaVariables(
      MinutaDraft draft, MinutaTemplateDescriptor descriptor, VariablesConsolidadas c) {
    return new VariablesMinutaResponse(
        draft == null ? null : draft.getId(),
        descriptor.templateKind(),
        descriptor.productCode(),
        draft == null ? null : draft.getStatus(),
        draft != null && draft.isEditedManually(),
        c.variables(),
        c.variablesPendientes(),
        c.etiquetas(),
        c.completo(),
        draft == null || !StringUtils.hasText(draft.getStoragePath())
            ? null
            : downloadUrl(draft.getId()));
  }

  /** Datos del crédito del JSON final, conservando las cifras BIESS que no viven en la minuta. */
  private static DatosBiessMinuta biessDesde(MinutaViviendaData data, DatosBiessMinuta previo) {
    DatosBiessMinuta actual = DatosBiessMinuta.from(data);
    if (previo == null) {
      return actual;
    }
    return new DatosBiessMinuta(
        actual.monto(),
        actual.tasa(),
        actual.plazo(),
        actual.cuota(),
        previo.valorReposicion(),
        previo.porcentajeValorFinanciado(),
        actual.apoderado());
  }

  private MinutaDraft ultimoDraft(WritingFile file, UUID tenantId, String kind) {
    MinutaDraft ultimo = null;
    for (MinutaDraft d :
        minutaDrafts.findByWritingFileIdAndTenantIdOrderByCreatedAtAsc(file.getId(), tenantId)) {
      if (kind.equalsIgnoreCase(d.getTemplateKind())) {
        ultimo = d;
      }
    }
    return ultimo;
  }

  private static String normalizarKind(String tipoMinuta) {
    return StringUtils.hasText(tipoMinuta)
        ? tipoMinuta.trim().toUpperCase(Locale.ROOT)
        : "MINUTA_COMPRAVENTA";
  }

  private static String productoDe(WritingFile file, LegalCase legalCase) {
    if (file != null && StringUtils.hasText(file.getProductCode())) {
      return file.getProductCode().trim();
    }
    return legalCase.getProductCode() == null ? "" : legalCase.getProductCode().trim();
  }

  private LegalCase requireCase(UUID caseId, UUID tenantId) {
    return legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
  }

  private WritingFile requireWritingFile(UUID caseId, UUID tenantId) {
    return writingFiles
        .findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.CONFLICT,
                    "NO_WRITING_FILE",
                    "Configura el producto BIESS antes de previsualizar la minuta."));
  }

  @Transactional
  public MinutaItem generar(UUID caseId, CrearMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    caseId = promocion.asegurarExpediente(caseId);
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
    MinutaViviendaData data =
        datosBase(
            tenantId,
            caseId,
            legalCase,
            request == null ? null : request.sessionId(),
            product,
            kind);
    validarCriticos(descriptor, data, caseId);

    byte[] docx = renderer.renderVivienda(descriptor, data);
    MinutaDraft draft = minutaDrafts.save(MinutaDraft.create(tenantId, file.getId(), product, kind));
    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, data);
    draft.markGenerated(stored.toString());
    minutaDrafts.save(draft);
    datosBiess.guardar(tenantId, caseId, DatosBiessMinuta.from(data));

    List<String> pendientes = pendientes(descriptor, data);
    LOG.info(
        "Minuta generada case={} product={} kind={} draft={} bytes={} pendientes={}",
        caseId,
        product,
        kind,
        draft.getId(),
        docx.length,
        pendientes.size());

    return toItem(draft, pendientes);
  }

  @Transactional(readOnly = true)
  public DatosBiessMinutaResponse obtenerDatosBiess(UUID caseId, UUID minutaId) {
    authorization.requirePermission("expedientes:caso:leer");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    MinutaViviendaData data = loadData(draft);
    List<String> pendientes =
        data == null
            ? List.of()
            : catalog
                .find(draft.getProductCode(), draft.getTemplateKind())
                .map(d -> pendientes(d, data))
                .orElse(List.of());
    return new DatosBiessMinutaResponse(
        toItem(draft, pendientes),
        data == null ? DatosBiessMinuta.empty() : DatosBiessMinuta.from(data));
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
      LOG.info("Minuta {} sin datos persistidos; se reconstruyen desde el expediente", draft.getId());
      data = extraerDatosLegacy(caseId);
    }

    byte[] docx = aplicarBiess(draft, descriptor, data, biess, leerDocx(draft));
    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, data);
    draft.markGenerated(stored.toString());
    minutaDrafts.save(draft);
    datosBiess.guardar(draft.getTenantId(), caseId, DatosBiessMinuta.from(data));

    LOG.info(
        "Datos BIESS aplicados case={} draft={} kind={} editado={} bytes={}",
        caseId,
        draft.getId(),
        draft.getTemplateKind(),
        draft.isEditedManually(),
        docx.length);
    return new DatosBiessMinutaResponse(
        toItem(draft, pendientes(descriptor, data)), DatosBiessMinuta.from(data));
  }

  /**
   * Sustituye en el documento los campos BIESS: re-render completo desde la plantilla si el DOCX
   * no tiene ediciones manuales; si las tiene, solo se parchean los tags afectados sobre
   * {@code docxActual} para no perder el texto editado.
   */
  private byte[] aplicarBiess(
      MinutaDraft draft,
      MinutaTemplateDescriptor descriptor,
      MinutaViviendaData data,
      DatosBiessMinuta biess,
      byte[] docxActual) {
    Map<String, Object> antes = renderer.valoresPorTag(descriptor, data);
    String montoAnterior = DatosBiessMinuta.from(data).monto();
    data.setMontoPrestamo(biess.monto());
    data.setTasaInteresInicial(biess.tasa());
    data.setPlazoCredito(biess.plazo());
    data.setCuotaCredito(biess.cuota());
    data.setApoderadoBiess(biess.apoderado());
    if (!montoAnterior.equals(biess.monto())) {
      // Evita un monto en letras que ya no coincide con la cifra; queda como dato pendiente.
      data.setMontoPrestamoLetras("");
    }
    return draft.isEditedManually()
        ? contenido.actualizarValores(
            docxActual,
            renderer.plantilla(descriptor),
            antes,
            renderer.valoresPorTag(descriptor, data),
            renderer.tagsDeCampos(descriptor, TAGS_BIESS))
        : renderer.renderVivienda(descriptor, data);
  }

  /**
   * Pipeline "Generar borrador": render determinístico (sin LLM sobre el texto legal) y payload
   * listo para el editor web: párrafos del DOCX + URL de descarga.
   */
  @Transactional
  public BorradorGeneradoResponse generarBorrador(
      UUID caseId, String tipoMinuta, CrearMinutaRequest request) {
    String sessionId = request == null ? null : request.sessionId();
    MinutaItem item = generar(caseId, new CrearMinutaRequest(tipoMinuta, sessionId));
    MinutaDraft draft =
        minutaDrafts.findById(item.id()).orElseThrow(() -> ApiException.notFound("Minuta no encontrada."));
    byte[] docx = leerDocx(draft);
    return new BorradorGeneradoResponse(
        draft.getId(),
        draft.getStatus(),
        item,
        draft.isEditedManually(),
        contenido.leer(docx),
        DatosBiessMinuta.from(loadData(draft)),
        downloadUrl(draft.getId()));
  }

  /**
   * "Guardar" del editor: texto editado por párrafo + datos BIESS del panel lateral en una sola
   * operación; el DOCX resultante es el que sirve la descarga.
   */
  @Transactional
  public MinutaGuardadaResponse guardar(UUID caseId, UUID minutaId, GuardarMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    MinutaTemplateDescriptor descriptor =
        catalog.require(draft.getProductCode(), draft.getTemplateKind());
    List<ParrafoEditado> cambios =
        request == null || request.parrafos() == null ? List.of() : request.parrafos();

    byte[] docx = leerDocx(draft);
    if (!cambios.isEmpty()) {
      docx = contenido.editar(docx, cambios);
      draft.markEditedManually();
    }

    MinutaViviendaData data = loadData(draft);
    if (data == null) {
      LOG.info("Minuta {} sin datos persistidos; se reconstruyen desde el expediente", draft.getId());
      data = extraerDatosLegacy(caseId);
    }
    DatosBiessMinuta biess = request == null ? null : request.datosBiess();
    boolean biessCambia = biess != null && !biess.equals(DatosBiessMinuta.from(data));
    if (biessCambia) {
      docx = aplicarBiess(draft, descriptor, data, biess, docx);
    }

    Path stored = persistDocx(draft.getId(), descriptor.fileName(), docx);
    persistData(draft, data);
    draft.markGenerated(stored.toString());
    minutaDrafts.save(draft);
    if (biessCambia) {
      datosBiess.guardar(draft.getTenantId(), caseId, DatosBiessMinuta.from(data));
    }
    LOG.info(
        "Minuta guardada case={} draft={} parrafos={} biess={} bytes={}",
        caseId,
        draft.getId(),
        cambios.size(),
        biessCambia,
        docx.length);
    return new MinutaGuardadaResponse(
        toItem(draft, pendientes(descriptor, data)),
        draft.isEditedManually(),
        contenido.leer(docx),
        DatosBiessMinuta.from(data),
        downloadUrl(draft.getId()));
  }

  /** Descarga por id de minuta (sin expediente en la ruta); resuelve el expediente desde el draft. */
  @Transactional(readOnly = true)
  public DownloadedMinuta descargarPorMinuta(UUID minutaId) {
    return descargar(caseIdDe(minutaId), minutaId);
  }

  @Transactional
  public MinutaGuardadaResponse guardarPorMinuta(UUID minutaId, GuardarMinutaRequest request) {
    return guardar(caseIdDe(minutaId), minutaId, request);
  }

  private UUID caseIdDe(UUID minutaId) {
    UUID tenantId = AuthContext.require().tenantId();
    MinutaDraft draft =
        minutaDrafts
            .findById(minutaId)
            .filter(m -> tenantId.equals(m.getTenantId()))
            .orElseThrow(() -> ApiException.notFound("Minuta no encontrada."));
    return writingFiles
        .findById(draft.getWritingFileId())
        .map(WritingFile::getCaseId)
        .orElseThrow(() -> ApiException.notFound("Escrituración no encontrada."));
  }

  public static String downloadUrl(UUID minutaId) {
    return "/api/v1/minutas/" + minutaId + "/download";
  }

  /** Párrafos del DOCX guardado: el mismo archivo que devuelve la descarga. */
  @Transactional(readOnly = true)
  public ContenidoMinutaResponse obtenerContenido(UUID caseId, UUID minutaId) {
    authorization.requirePermission("expedientes:caso:leer");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    return contenidoResponse(draft, leerDocx(draft));
  }

  /** Guarda el texto editado sobre el mismo DOCX y marca el borrador como editado a mano. */
  @Transactional
  public ContenidoMinutaResponse guardarContenido(
      UUID caseId, UUID minutaId, GuardarContenidoMinutaRequest request) {
    authorization.requirePermission("expedientes:caso:escribir");
    MinutaDraft draft = requireDraft(caseId, minutaId);
    List<ParrafoEditado> cambios =
        request == null || request.parrafos() == null ? List.of() : request.parrafos();
    byte[] docx = contenido.editar(leerDocx(draft), cambios);
    String fileName =
        catalog
            .find(draft.getProductCode(), draft.getTemplateKind())
            .map(MinutaTemplateDescriptor::fileName)
            .orElse("minuta.docx");
    Path stored = persistDocx(draft.getId(), fileName, docx);
    if (!cambios.isEmpty()) {
      draft.markEditedManually();
    }
    draft.markGenerated(stored.toString());
    minutaDrafts.save(draft);
    LOG.info(
        "Contenido de minuta guardado case={} draft={} parrafos={} bytes={}",
        caseId,
        draft.getId(),
        cambios.size(),
        docx.length);
    return contenidoResponse(draft, docx);
  }

  private ContenidoMinutaResponse contenidoResponse(MinutaDraft draft, byte[] docx) {
    MinutaViviendaData data = loadData(draft);
    List<String> pendientes =
        data == null
            ? List.of()
            : catalog
                .find(draft.getProductCode(), draft.getTemplateKind())
                .map(d -> pendientes(d, data))
                .orElse(List.of());
    return new ContenidoMinutaResponse(
        toItem(draft, pendientes), draft.isEditedManually(), contenido.leer(docx));
  }

  private byte[] leerDocx(MinutaDraft draft) {
    if (!StringUtils.hasText(draft.getStoragePath())) {
      throw ApiException.badRequest("La minuta aún no tiene archivo generado.");
    }
    Path path = Path.of(draft.getStoragePath());
    if (!Files.isRegularFile(path)) {
      throw ApiException.notFound("Archivo DOCX no encontrado en almacenamiento.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo de la minuta.");
    }
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
    return toItem(draft, List.of());
  }

  private static MinutaItem toItem(MinutaDraft draft, List<String> camposPendientes) {
    return new MinutaItem(
        draft.getId(),
        draft.getTemplateKind(),
        draft.getProductCode(),
        draft.getStatus(),
        StringUtils.hasText(draft.getStoragePath()),
        camposPendientes);
  }

  public record DownloadedMinuta(String fileName, byte[] bytes) {}

  private List<String> pendientes(MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    return renderer.camposPendientes(descriptor, data).stream()
        .map(MinutaViviendaData::etiqueta)
        .toList();
  }

  /**
   * Solo los críticos del descriptor bloquean (422); el resto de tags vacíos se genera como nodata y
   * se devuelve en camposPendientes.
   */
  private static void validarCriticos(
      MinutaTemplateDescriptor descriptor, MinutaViviendaData data, UUID caseId) {
    var map = data.toTemplateMap();
    List<String> faltantes =
        descriptor.requiredFields().stream()
            .filter(campo -> MinutaViviendaData.isMissing(map.get(campo)))
            .toList();
    if (!faltantes.isEmpty()) {
      LOG.warn(
          "Minuta case={} kind={} no generada, faltan críticos: {}",
          caseId,
          descriptor.templateKind(),
          faltantes);
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "MINUTA_DATOS_FALTANTES",
          "No se generó el documento: faltan datos obligatorios en el expediente: "
              + String.join("; ", faltantes.stream().map(MinutaViviendaData::etiqueta).toList())
              + ".");
    }
  }

  /**
   * Datos del documento sin depender del LLM: la extracción IA (si está disponible) es solo un
   * enriquecimiento; el consolidado del expediente y la captura BIESS guardada completan el resto.
   */
  private MinutaViviendaData datosBase(
      UUID tenantId,
      UUID caseId,
      LegalCase legalCase,
      String sessionIdHint,
      String productCode,
      String templateKind) {
    MinutaViviendaData data = extraerConIa(legalCase, sessionIdHint, productCode, templateKind);
    completarDesdeConsolidado(tenantId, caseId, data);
    aplicarDatosBiessGuardados(tenantId, caseId, data);
    return data;
  }

  private MinutaViviendaData extraerConIa(
      LegalCase legalCase, String sessionIdHint, String productCode, String templateKind) {
    if (!analisis.isConfigured()) {
      LOG.info("Minuta case={}: LLM no configurado, se usa solo el expediente", legalCase.getId());
      return new MinutaViviendaData();
    }
    String ocr = findOcrConsolidated(legalCase, sessionIdHint);
    if (ocr == null) {
      LOG.info("Minuta case={}: sin OCR consolidado, se omite la extracción IA", legalCase.getId());
      return new MinutaViviendaData();
    }
    try {
      ExtraccionMinutaVivienda extraccion =
          analisis.extraerMinutaVivienda(ocr, productCode, templateKind);
      if (extraccion == null || "ERROR".equals(extraccion.estado()) || extraccion.data() == null) {
        LOG.warn(
            "Minuta case={}: extracción IA no disponible ({}); se usa solo el expediente",
            legalCase.getId(),
            extraccion == null ? "sin respuesta" : extraccion.motivo());
        return new MinutaViviendaData();
      }
      return extraccion.data();
    } catch (RuntimeException e) {
      LOG.warn(
          "Minuta case={}: fallo del LLM ({}); se usa solo el expediente",
          legalCase.getId(),
          e.getMessage());
      return new MinutaViviendaData();
    }
  }

  /**
   * Respaldo con el consolidado del estudio IA ({@code extracted_data}): solo completa lo que la
   * extracción de minuta dejó vacío.
   */
  private void completarDesdeConsolidado(UUID tenantId, UUID caseId, MinutaViviendaData data) {
    Map<String, String> consolidado = new HashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(caseId, tenantId)) {
      if (row.getFieldLabel() != null && StringUtils.hasText(row.getFieldValue())) {
        consolidado.put(row.getFieldLabel(), row.getFieldValue().trim());
      }
    }
    if (consolidado.isEmpty()) {
      return;
    }
    completar(data.getNombreConyuge1(), consolidado.get("comprador.nombres"), data::setNombreConyuge1);
    completar(data.getCedulaConyuge1(), consolidado.get("comprador.cedula"), data::setCedulaConyuge1);
    completar(data.getEstadoCivil(), consolidado.get("comprador.estadoCivil"), data::setEstadoCivil);
    completar(data.getNombreVendedor(), consolidado.get("vendedor.nombres"), data::setNombreVendedor);
    completar(data.getCedulaVendedor(), consolidado.get("vendedor.cedula"), data::setCedulaVendedor);
    completar(
        data.getEstadoCivilVendedor(),
        consolidado.get("vendedor.estadoCivil"),
        data::setEstadoCivilVendedor);
    completar(
        data.getClaveCatastral(), consolidado.get("inmueble.claveCatastral"), data::setClaveCatastral);
    completar(
        data.getAvaluoInmueble(),
        montoConDecimales(consolidado.get("inmueble.avaluo")),
        data::setAvaluoInmueble);
  }

  /** Captura BIESS ya guardada en el expediente (p. ej. al generar la minuta antes que el mutuo). */
  private void aplicarDatosBiessGuardados(UUID tenantId, UUID caseId, MinutaViviendaData data) {
    DatosBiessMinuta guardados = datosBiess.cargar(caseId, tenantId);
    if (guardados == null) {
      return;
    }
    if (StringUtils.hasText(guardados.monto())
        && !guardados.monto().equals(DatosBiessMinuta.from(data).monto())) {
      data.setMontoPrestamo(guardados.monto());
      data.setMontoPrestamoLetras("");
    }
    if (StringUtils.hasText(guardados.tasa())) {
      data.setTasaInteresInicial(guardados.tasa());
    }
    if (StringUtils.hasText(guardados.plazo())) {
      data.setPlazoCredito(guardados.plazo());
    }
    if (StringUtils.hasText(guardados.cuota())) {
      data.setCuotaCredito(guardados.cuota());
    }
    if (StringUtils.hasText(guardados.apoderado())) {
      data.setApoderadoBiess(guardados.apoderado());
    }
  }

  private static void completar(String actual, String candidato, Consumer<String> setter) {
    if (MinutaViviendaData.isMissing(actual) && StringUtils.hasText(candidato)) {
      setter.accept(candidato);
    }
  }

  private static String montoConDecimales(String raw) {
    if (!StringUtils.hasText(raw)) {
      return raw;
    }
    try {
      return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP).toPlainString();
    } catch (NumberFormatException e) {
      return raw.trim();
    }
  }

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
    return datosBase(tenantId, caseId, legalCase, null, legalCase.getProductCode(), null);
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

  private String findOcrConsolidated(LegalCase legalCase, String sessionIdHint) {
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
    return null;
  }
}
