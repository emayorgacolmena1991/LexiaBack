package com.lexia.api.modules.ia.ocr;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Modo;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.ReemplazoPreparado;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Deteccion;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchDocument;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchResultItem;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidatedGetResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ReuploadResponse;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService.OcrFileResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pantalla 2: Azure OCR + calidad (sin Gemini/Claude). Resultados en caché de sesión.
 * Batch: paralelo vía {@code ocrExecutor} (S0).
 */
@Service
public class OcrAzureBatchService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrAzureBatchService.class);
  static final String OCR_FALLIDO =
      "No se pudo leer el archivo nuevo con OCR; se conserva el texto anterior.";
  static final String TIPO_DISTINTO =
      "El archivo parece ser de otro tipo; confirme para reemplazar.";
  /** Cuánto se guarda la validación de un reemplazo a la espera de la confirmación del usuario. */
  static final Duration VALIDACION_TTL = Duration.ofMinutes(10);
  private static final int VALIDACIONES_MAX = 200;

  private final AzureCalidadDocumentoService layoutService;
  private final CargaDocumentoService cargaDocumentoService;
  private final OcrSessionCacheService cache;
  private final DocumentoTextoOcrRepository ocrRepository;
  private final Executor ocrExecutor;
  private final ClasificacionDocumentoService clasificador;
  private final TransactionTemplate transaccion;
  /**
   * OCR y detección de un reemplazo que espera confirmación de tipo: al confirmar no se vuelve a
   * llamar a Azure ni al LLM. Clave: usuario + sesión + documento + hash + tipo; vence en
   * {@link #VALIDACION_TTL}.
   */
  private final Map<String, Validacion> validaciones = new ConcurrentHashMap<>();

  public OcrAzureBatchService(
      AzureCalidadDocumentoService layoutService,
      CargaDocumentoService cargaDocumentoService,
      OcrSessionCacheService cache,
      DocumentoTextoOcrRepository ocrRepository,
      @Qualifier("ocrExecutor") Executor ocrExecutor,
      ClasificacionDocumentoService clasificador,
      PlatformTransactionManager transactionManager) {
    this.layoutService = layoutService;
    this.cargaDocumentoService = cargaDocumentoService;
    this.cache = cache;
    this.ocrRepository = ocrRepository;
    this.ocrExecutor = ocrExecutor;
    this.clasificador = clasificador;
    this.transaccion = new TransactionTemplate(transactionManager);
  }

  public AnalyzeBatchResponse analyzeBatch(AnalyzeBatchRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    if (request.documents() == null || request.documents().isEmpty()) {
      throw ApiException.badRequest("documents requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    String sessionId = request.sessionId().trim();
    List<CompletableFuture<AnalyzeBatchResultItem>> futures = new ArrayList<>();

    for (AnalyzeBatchDocument doc : request.documents()) {
      if (doc == null || !StringUtils.hasText(doc.fileId())) {
        throw ApiException.badRequest("fileId requerido en cada documento.");
      }
      AnalyzeBatchDocument snapshot = doc;
      futures.add(
          CompletableFuture.supplyAsync(
              () -> procesarDocumentoBatch(sessionId, snapshot), ocrExecutor));
    }

    List<AnalyzeBatchResultItem> results = new ArrayList<>(futures.size());
    boolean anyIllegible = false;
    boolean anyError = false;

    for (CompletableFuture<AnalyzeBatchResultItem> future : futures) {
      try {
        AnalyzeBatchResultItem item = future.join();
        results.add(item);
        if (!item.legible()) {
          anyIllegible = true;
        }
        if (StringUtils.hasText(item.motivo()) && item.motivo().startsWith("Error Azure OCR:")) {
          anyError = true;
        }
      } catch (CompletionException ce) {
        Throwable cause = ce.getCause() == null ? ce : ce.getCause();
        if (cause instanceof ApiException apiEx) {
          throw apiEx;
        }
        throw new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "OCR_BATCH_FAILED",
            "Fallo OCR batch: " + safe(cause instanceof Exception e ? e : new Exception(cause)));
      }
    }

    String status =
        anyError
            ? "COMPLETED_WITH_ERRORS"
            : (anyIllegible ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
    return new AnalyzeBatchResponse(sessionId, status, results);
  }

  private AnalyzeBatchResultItem procesarDocumentoBatch(
      String sessionId, AnalyzeBatchDocument doc) {
    String fileId = doc.fileId().trim();
    String tipo =
        StringUtils.hasText(doc.tipoDocumento()) ? doc.tipoDocumento().trim() : null;
    try {
      StoredDoc stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
      if (StringUtils.hasText(tipo) && !tipo.equals(stored.codigoTipoDocumento())) {
        cargaDocumentoService.actualizarTipoDocumentoLibre(sessionId, fileId, tipo);
        stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
      }
      // El tipo del cliente ya se validó contra el catálogo; si no correspondía, no viaja a la IA.
      String tipoFinal =
          StringUtils.hasText(stored.codigoTipoDocumento()) ? stored.codigoTipoDocumento() : "DOCUMENTO";
      return procesarArchivo(sessionId, stored, tipoFinal, Modo.ASIGNAR).item();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("OCR batch fileId={} err={}", fileId, e.getMessage());
      AnalyzeBatchResultItem fail =
          new AnalyzeBatchResultItem(
              fileId,
              tipo == null ? "DOCUMENTO" : tipo,
              false,
              "Error Azure OCR: " + safe(e),
              0.0,
              "");
      cache.putResult(
          sessionId,
          new OcrFileResult(
              fail.fileId(),
              doc.fileName(),
              fail.tipoDocumento(),
              false,
              fail.motivo(),
              0.0,
              ""));
      return fail;
    }
  }

  public AnalyzeSingleResponse analyzeSingle(AnalyzeSingleRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    if (!StringUtils.hasText(request.fileId())) {
      throw ApiException.badRequest("fileId requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    String sessionId = request.sessionId().trim();
    String fileId = request.fileId().trim();
    String tipo =
        StringUtils.hasText(request.tipoDocumento()) ? request.tipoDocumento().trim() : null;

    StoredDoc stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
    if (StringUtils.hasText(tipo) && !tipo.equals(stored.codigoTipoDocumento())) {
      cargaDocumentoService.actualizarTipoDocumentoLibre(sessionId, fileId, tipo);
      stored = cargaDocumentoService.requireStoredDoc(sessionId, fileId);
    }
    // El tipo del cliente ya se validó contra el catálogo; si no correspondía, no viaja a la IA.
    String tipoFinal =
        StringUtils.hasText(stored.codigoTipoDocumento()) ? stored.codigoTipoDocumento() : "DOCUMENTO";

    AnalyzeBatchResultItem item = procesarArchivo(sessionId, stored, tipoFinal, Modo.ASIGNAR).item();
    String estado = item.legible() ? "LEGIBLE" : "REVISAR";
    return new AnalyzeSingleResponse(
        item.fileId(),
        stored.nombreOriginal(),
        item.tipoDocumento(),
        item.legible(),
        item.motivo(),
        item.scoreConfianza(),
        item.textoExtraido(),
        estado);
  }

  public LayoutExtractResult evaluarYExtraer(byte[] bytes, String mimeType) {
    if (bytes == null || bytes.length == 0) {
      throw ApiException.badRequest("file requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }
    return layoutService.evaluarYExtraer(bytes, mimeType);
  }

  public String extraerTexto(byte[] bytes, String mimeType) {
    LayoutExtractResult layout = evaluarYExtraer(bytes, mimeType);
    return layout.texto() == null ? "" : layout.texto();
  }

  /**
   * Reemplazo validado antes de guardar:
   *
   * <ol>
   *   <li>OCR de los bytes recibidos sin escribir nada; si falla, no se persiste nada.
   *   <li>Clasificación SOLO_DETECTAR sin escribir (escaneado no clasifica). Un ERROR del LLM no
   *       bloquea: se trata como coincidente.
   *   <li>Tipo detectado distinto del que tendrá el documento y sin {@code confirmarTipoDistinto}:
   *       no se persiste nada y se pide confirmación.
   *   <li>Si no: archivo, versión N+1, fila OCR, clasificación y {@code consolidado} en una sola
   *       transacción (el archivo se borra si se revierte); la caché, después del commit.
   * </ol>
   */
  public ReuploadResponse reupload(
      String sessionId,
      String fileId,
      String tipoDocumento,
      MultipartFile file,
      boolean confirmarTipoDistinto) {
    if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(fileId)) {
      throw ApiException.badRequest("sessionId y fileId requeridos.");
    }
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("file requerido.");
    }
    if (!layoutService.isConfigured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AZURE_NOT_CONFIGURED",
          "Azure Document Intelligence no configurado.");
    }

    String sesion = sessionId.trim();
    ReemplazoPreparado preparado =
        cargaDocumentoService.prepararReemplazo(sesion, fileId.trim(), file, tipoDocumento);
    String clave = claveValidacion(sesion, preparado);
    Validacion validacion = confirmarTipoDistinto ? tomarValidacion(clave) : null;
    if (validacion == null) {
      LayoutExtractResult layout;
      try {
        layout = layoutService.evaluarYExtraer(preparado.bytes(), preparado.mime());
      } catch (RuntimeException e) {
        LOG.warn(
            "Reemplazo sin guardar sesion={} doc={}: OCR falló ({})",
            sesion,
            preparado.fileId(),
            safe(e));
        return sinGuardar(sesion, preparado, OCR_FALLIDO, false, null, false);
      }
      String texto = layout.texto() == null ? "" : layout.texto();
      validacion = new Validacion(layout, detectar(sesion, preparado.fileId(), texto), Instant.now());
    }
    Deteccion deteccion = validacion.deteccion();
    boolean distinto =
        deteccion.ok()
            && StringUtils.hasText(preparado.tipoFinal())
            && !deteccion.tipo().equals(preparado.tipoFinal());
    if (distinto && !confirmarTipoDistinto) {
      guardarValidacion(clave, validacion);
      return sinGuardar(sesion, preparado, TIPO_DISTINTO, true, deteccion.tipo(), true);
    }

    String tipo = StringUtils.hasText(preparado.tipoFinal()) ? preparado.tipoFinal() : "DOCUMENTO";
    LayoutExtractResult layout = validacion.layout();
    Guardado guardado =
        transaccion.execute(
            status -> {
              StoredDoc nuevo = cargaDocumentoService.guardarReemplazo(preparado);
              AnalyzeBatchResultItem item = itemDe(nuevo, tipo, layout);
              guardarFilaOcr(sesion, nuevo, item);
              clasificador.registrarDeteccion(sesion, preparado.fileId(), deteccion);
              return new Guardado(nuevo, item, regenerarConsolidado(sesion, null));
            });
    // Caché de sesión solo con la transacción confirmada.
    ponerEnCache(sesion, guardado.documento(), guardado.item());
    cache.putConsolidated(sesion, guardado.consolidado());
    AnalyzeBatchResultItem item = guardado.item();
    return new ReuploadResponse(
        item.fileId(),
        guardado.documento().nombreOriginal(),
        item.tipoDocumento(),
        item.legible(),
        item.scoreConfianza(),
        item.textoExtraido(),
        item.motivo(),
        preparado.fileId(),
        cargaDocumentoService.versionActual(sesion, preparado.fileId()),
        true,
        deteccion.ok() ? deteccion.tipo() : null,
        deteccion.ok() ? Boolean.valueOf(!distinto) : (deteccion.error() ? Boolean.TRUE : null),
        true,
        false);
  }

  /**
   * Nada se guardó: la caché y {@code documento_texto_ocr} siguen con el texto anterior, que es el
   * que se devuelve para que la pantalla no muestre un texto que el sistema no tiene.
   */
  private ReuploadResponse sinGuardar(
      String sesion,
      ReemplazoPreparado preparado,
      String motivo,
      boolean ocrOk,
      String tipoDetectado,
      boolean requiereConfirmacion) {
    OcrFileResult anterior =
        cache.listResults(sesion).stream()
            .filter(r -> preparado.fileId().equals(r.fileId()))
            .findFirst()
            .orElse(null);
    return new ReuploadResponse(
        preparado.fileId(),
        preparado.nombre(),
        StringUtils.hasText(preparado.tipoActual()) ? preparado.tipoActual() : "DOCUMENTO",
        anterior != null && anterior.legible(),
        anterior == null ? 0.0 : anterior.scoreConfianza(),
        anterior == null ? null : anterior.textoExtraido(),
        motivo,
        preparado.fileId(),
        cargaDocumentoService.versionActual(sesion, preparado.fileId()),
        ocrOk,
        tipoDetectado,
        requiereConfirmacion ? Boolean.FALSE : null,
        false,
        requiereConfirmacion);
  }

  /** Lo que se guarda dentro de la transacción del reemplazo. */
  private record Guardado(StoredDoc documento, AnalyzeBatchResultItem item, String consolidado) {}

  /** OCR + detección de un reemplazo pendiente de confirmación. */
  private record Validacion(LayoutExtractResult layout, Deteccion deteccion, Instant creada) {

    boolean vigente() {
      return creada.plus(VALIDACION_TTL).isAfter(Instant.now());
    }
  }

  private Validacion tomarValidacion(String clave) {
    Validacion validacion = validaciones.remove(clave);
    return validacion != null && validacion.vigente() ? validacion : null;
  }

  private void guardarValidacion(String clave, Validacion validacion) {
    validaciones.values().removeIf(v -> !v.vigente());
    if (validaciones.size() >= VALIDACIONES_MAX) {
      // Tope de memoria: sin sitio, la confirmación vuelve a hacer el OCR (no se pierde nada).
      return;
    }
    validaciones.put(clave, validacion);
  }

  /** Mismo usuario, misma sesión, mismo documento, mismos bytes y mismo tipo destino. */
  private static String claveValidacion(String sesion, ReemplazoPreparado preparado) {
    AuthPrincipal principal = AuthContext.get();
    String usuario = principal == null || principal.userId() == null ? "-" : principal.userId().toString();
    return String.join(
        "|", usuario, sesion, preparado.fileId(), sha256(preparado.bytes()), String.valueOf(preparado.tipoFinal()));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }

  /** Un fallo de lectura previo a la detección no bloquea el reemplazo: cuenta como ERROR. */
  private Deteccion detectar(String sesion, String idDocumento, String texto) {
    try {
      return clasificador.detectar(sesion, idDocumento, texto);
    } catch (RuntimeException e) {
      LOG.error("Detección de tipo no disponible doc={}: {}", idDocumento, e.getClass().getSimpleName(), e);
      return new Deteccion(Deteccion.ERROR, null, null, null);
    }
  }

  /**
   * Upload a un expediente persistido: mismo pipeline que analyze-single (Azure + caché + fila OCR)
   * y regenera {@code consolidado}. En un borrador devuelve {@code null}: ahí el OCR lo dispara el
   * wizard. Un fallo de OCR no tumba el upload: el archivo ya quedó guardado.
   */
  public Boolean ocrTrasSubir(String idExpediente, String idDocumento) {
    if (!StringUtils.hasText(idExpediente)
        || !StringUtils.hasText(idDocumento)
        || !cargaDocumentoService.esExpedientePersistido(idExpediente)) {
      return null;
    }
    if (!layoutService.isConfigured()) {
      LOG.warn("OCR al subir omitido expediente={} doc={}: Azure no configurado", idExpediente, idDocumento);
      return false;
    }
    String sesion = idExpediente.trim();
    StoredDoc stored = cargaDocumentoService.requireStoredDoc(sesion, idDocumento.trim());
    String tipo =
        StringUtils.hasText(stored.codigoTipoDocumento()) ? stored.codigoTipoDocumento() : "DOCUMENTO";
    return ocrYConsolidar(sesion, stored, tipo, Modo.ASIGNAR) != null;
  }

  /** {@code null} si Azure falla: no se toca ni la caché ni la fila OCR anterior. */
  private Procesado ocrYConsolidar(String sesion, StoredDoc stored, String tipo, Modo modo) {
    Procesado procesado;
    try {
      procesado = procesarArchivo(sesion, stored, tipo, modo);
    } catch (RuntimeException e) {
      LOG.warn(
          "OCR falló sesion={} doc={}: {}; se conserva el texto anterior",
          sesion,
          stored.idDocumento(),
          safe(e));
      return null;
    }
    consolidate(new ConsolidateRequest(sesion, null));
    return procesado;
  }

  public ConsolidateResponse consolidate(ConsolidateRequest request) {
    if (request == null || !StringUtils.hasText(request.sessionId())) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String sessionId = request.sessionId().trim();
    cache.putConsolidated(sessionId, regenerarConsolidado(sessionId, request.consolidatedContent()));
    return new ConsolidateResponse(true, OcrSessionCacheService.cacheKey(sessionId));
  }

  /** Reescribe la fila {@code consolidado} con las filas por documento; no toca la caché. */
  private String regenerarConsolidado(String sessionId, String alternativo) {
    StringBuilder unido = new StringBuilder();
    for (DocumentoTextoOcr row : ocrRepository.findByIdExpedienteOrderByCreatedAtAsc(sessionId)) {
      if (row == null || "consolidado".equals(row.getIdDocumento()) || !StringUtils.hasText(row.getTextoOcr())) {
        continue;
      }
      String tipo = StringUtils.hasText(row.getTipoDocumento()) ? row.getTipoDocumento() : "DOCUMENTO";
      unido.append("=== DOCUMENTO: ").append(tipo).append(" ===\n");
      unido.append(row.getTextoOcr().trim()).append("\n\n");
    }
    String content = unido.toString().trim();
    if (!StringUtils.hasText(content)) {
      content = alternativo == null ? "" : alternativo;
    }
    int confianza = 100;
    cargaDocumentoService.guardarResultadoOcr(
        sessionId,
        new DocumentoOcrResultadoDTO(
            "consolidado", "consolidado", "CONSOLIDADO", content, null, "LEGIBLE", null, confianza));
    return content;
  }

  public ConsolidatedGetResponse getConsolidated(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    String content = cache.getConsolidated(id);
    if (content == null) {
      throw ApiException.notFound("Caché OCR no encontrada o expirada para sessionId=" + id);
    }
    return new ConsolidatedGetResponse(id, OcrSessionCacheService.cacheKey(id), content);
  }

  /** Resultado del pipeline de un archivo: OCR + clasificación. */
  private record Procesado(
      AnalyzeBatchResultItem item, ClasificacionDocumentoService.Resultado clasificacion) {}

  private Procesado procesarArchivo(
      String sessionId, StoredDoc stored, String tipoDocumento, Modo modo) {
    LayoutExtractResult layout =
        layoutService.evaluarYExtraer(stored.bytes(), stored.mimeType());
    String texto = layout.texto() == null ? "" : layout.texto();

    // Justo después del OCR: si asigna tipo, caché y fila OCR ya llevan ese tipo (<documento tipo>).
    ClasificacionDocumentoService.Resultado clasificacion =
        clasificar(sessionId, stored.idDocumento(), texto, modo);
    String tipoFinal =
        clasificacion.tipoAsignado() != null ? clasificacion.tipoAsignado() : tipoDocumento;

    AnalyzeBatchResultItem item = itemDe(stored, tipoFinal, layout);
    ponerEnCache(sessionId, stored, item);
    guardarFilaOcr(sessionId, stored, item);
    return new Procesado(item, clasificacion);
  }

  private static AnalyzeBatchResultItem itemDe(
      StoredDoc stored, String tipoDocumento, LayoutExtractResult layout) {
    CalidadDocumentoResultado q = layout.calidad();
    return new AnalyzeBatchResultItem(
        stored.idDocumento(),
        tipoDocumento,
        q.legible(),
        q.legible() ? null : q.mensaje(),
        q.promedioConfianza(),
        layout.texto() == null ? "" : layout.texto());
  }

  private void ponerEnCache(String sessionId, StoredDoc stored, AnalyzeBatchResultItem item) {
    cache.putResult(
        sessionId,
        new OcrFileResult(
            item.fileId(),
            stored.nombreOriginal(),
            item.tipoDocumento(),
            item.legible(),
            item.motivo(),
            item.scoreConfianza(),
            item.textoExtraido()));
  }

  private void guardarFilaOcr(String sessionId, StoredDoc stored, AnalyzeBatchResultItem item) {
    int confianza =
        (int)
            Math.round(
                item.scoreConfianza() <= 1 ? item.scoreConfianza() * 100 : item.scoreConfianza());
    cargaDocumentoService.guardarResultadoOcr(
        sessionId,
        new DocumentoOcrResultadoDTO(
            item.fileId(),
            stored.nombreOriginal(),
            item.tipoDocumento(),
            item.textoExtraido(),
            null,
            item.legible() ? "LEGIBLE" : "REVISAR",
            item.motivo(),
            confianza));
  }

  /**
   * La clasificación es complementaria: un fallo al guardarla tampoco rompe el OCR. El
   * clasificador ya convierte los fallos del LLM en estado ERROR; aquí solo llegan fallos de BD.
   */
  private ClasificacionDocumentoService.Resultado clasificar(
      String sessionId, String idDocumento, String texto, Modo modo) {
    try {
      return clasificador.clasificar(sessionId, idDocumento, texto, modo);
    } catch (RuntimeException e) {
      LOG.error(
          "Clasificación no aplicada doc={}: {}", idDocumento, e.getClass().getSimpleName(), e);
      return ClasificacionDocumentoService.Resultado.OMITIDO;
    }
  }

  private static String safe(Exception e) {
    String m = e.getMessage();
    if (!StringUtils.hasText(m)) {
      return e.getClass().getSimpleName();
    }
    return m.length() > 180 ? m.substring(0, 180) + "…" : m;
  }
}
