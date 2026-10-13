package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.PromoverBorradorRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.IngestionMode;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.CrearBorradorRequest;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import com.lexia.api.modules.expedientes.escrituracion.ProcesarExpedienteCompletoResult;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.ArchivoStatus;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.AsignacionArchivo;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.AsignacionesPayload;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.CrearLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.FilaRequest;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.FilaStatus;
import com.lexia.api.modules.expedientes.escrituracion.ingesta.IngestaMasivaDtos.LoteStatus;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleRequest;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeSingleResponse;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.ConsolidateRequest;
import com.lexia.api.modules.ia.llm.IaConsumoContexto;
import com.lexia.api.modules.ia.ocr.OcrTextSanitizerService;
import com.lexia.api.modules.tenancy.TenantContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Lote de escrituración: crea N expedientes, sube archivos ya clasificados, corre OCR y
 * semáforo, y dispara el estudio IA. Reutiliza borrador, promoción, carga, Azure y analizar-ia.
 */
@Service
public class IngestaMasivaEscrituracionService {

  private static final Logger LOG = LoggerFactory.getLogger(IngestaMasivaEscrituracionService.class);
  private static final int MAX_FILAS = 30;
  private static final int TEXTO_PREVIEW = 1500;

  private final CargaDocumentoService carga;
  private final CaseDocumentoStore casos;
  private final ExpedienteBorradorStore borradores;
  private final BorradorPromocionService promocion;
  private final OcrAzureBatchService ocr;
  private final IaAnalysisService ia;
  private final ProductoBiessService productos;
  private final IngestaLoteStore store;
  private final BatchEventPublisherService eventos;
  private final OcrTextSanitizerService sanitizer;
  private final IngestaJobQueue jobs;
  private final ObjectMapper json;
  private final Executor ocrExecutor;
  private final ConcurrentHashMap<UUID, Lote> lotes = new ConcurrentHashMap<>();
  private final Set<String> enVuelo = ConcurrentHashMap.newKeySet();
  /** ponytail: 4 llamadas Azure a la vez. Subir el permiso si cambia la cuota. */
  private final Semaphore azure = new Semaphore(4);

  public IngestaMasivaEscrituracionService(
      CargaDocumentoService carga,
      CaseDocumentoStore casos,
      ExpedienteBorradorStore borradores,
      BorradorPromocionService promocion,
      OcrAzureBatchService ocr,
      IaAnalysisService ia,
      ProductoBiessService productos,
      IngestaLoteStore store,
      BatchEventPublisherService eventos,
      OcrTextSanitizerService sanitizer,
      IngestaJobQueue jobs,
      ObjectMapper json,
      @Qualifier("ocrExecutor") Executor ocrExecutor) {
    this.carga = carga;
    this.casos = casos;
    this.borradores = borradores;
    this.promocion = promocion;
    this.ocr = ocr;
    this.ia = ia;
    this.productos = productos;
    this.store = store;
    this.eventos = eventos;
    this.sanitizer = sanitizer;
    this.jobs = jobs;
    this.json = json;
    this.ocrExecutor = ocrExecutor;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus crear(CrearLoteRequest request) {
    AuthPrincipal principal = AuthContext.require();
    if (request == null || request.filas() == null || request.filas().isEmpty()) {
      throw ApiException.badRequest("El lote no tiene filas.");
    }
    if (request.filas().size() > MAX_FILAS) {
      throw ApiException.badRequest("El lote admite hasta " + MAX_FILAS + " filas.");
    }
    Set<String> ids = new LinkedHashSet<>();
    Lote lote = new Lote(UUID.randomUUID(), principal.tenantId());
    for (FilaRequest fila : request.filas()) {
      if (!ids.add(fila.clientRowId().trim())) {
        throw ApiException.badRequest("clientRowId repetido: " + fila.clientRowId());
      }
      lote.filas.add(crearFila(principal, fila));
    }
    lote.fase = "CREADO";
    lotes.put(lote.id, lote);
    persistir(lote);
    LOG.info("ingesta-masiva lote={} filas={} tenant={}", lote.id, lote.filas.size(), principal.tenantId());
    return lote.snapshot();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus subir(UUID batchId, AsignacionesPayload payload, List<MultipartFile> files) {
    Lote lote = require(batchId);
    AuthContext.require();
    List<AsignacionArchivo> asignaciones = payload == null ? List.of() : payload.archivos();
    List<MultipartFile> binarios = files == null ? List.of() : files;
    if (asignaciones.isEmpty() || asignaciones.size() != binarios.size()) {
      throw ApiException.badRequest("Cada archivo debe traer su asignación (clientRowId y tipo).");
    }
    List<Fila> tocadas = new ArrayList<>();
    for (int i = 0; i < asignaciones.size(); i++) {
      AsignacionArchivo asignacion = asignaciones.get(i);
      MultipartFile file = binarios.get(i);
      Fila fila = fila(lote, asignacion.clientRowId());
      exigirBorrador(fila, asignacion.borradorId());
      String sesion = exigirExpediente(fila);
      if (file == null || file.isEmpty()) {
        throw ApiException.badRequest("Archivo vacío en la fila " + fila.clientRowId + ".");
      }
      String tipo = tipoDe(fila, asignacion.tipoDocumento());
      DocumentoCargadoDTO doc = carga.subir(sesion, file);
      if (!IngestionMode.FISICO_ESCANEADO.name().equals(fila.ingestionMode)) {
        carga.actualizarTipo(sesion, doc.idDocumento(), new ActualizarTipoRequest(tipo));
      }
      synchronized (fila) {
        fila.archivos.add(Archivo.nuevo(doc.idDocumento(), doc.nombreOriginal(), tipo));
        fila.ocrEstado = "PENDIENTE";
        fila.semaforo = "GRIS";
        fila.observaciones = null;
      }
      if (!tocadas.contains(fila)) {
        tocadas.add(fila);
      }
    }
    if (tocadas.isEmpty()) {
      throw ApiException.badRequest("No se guardó ningún archivo.");
    }
    synchronized (lote) {
      lote.fase = "ARCHIVOS";
    }
    persistir(lote);
    return lote.snapshot();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus ejecutarOcr(UUID batchId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    List<Fila> objetivo = new ArrayList<>();
    for (Fila fila : lote.filas) {
      synchronized (fila) {
        if (fila.expedienteId == null || fila.archivos.isEmpty()) {
          fila.ocrEstado = "ERROR";
          fila.semaforo = "ROJO";
          fila.observaciones = "Sin archivos guardados. No se llama a OCR.";
          continue;
        }
        if ("LISTO".equals(fila.ocrEstado) && !"ROJO".equals(fila.semaforo)) {
          continue;
        }
        fila.ocrEstado = "PROCESANDO";
        fila.semaforo = "GRIS";
        fila.observaciones = null;
        objetivo.add(fila);
      }
    }
    if (objetivo.isEmpty()) {
      throw ApiException.badRequest("No hay archivos guardados para OCR.");
    }
    synchronized (lote) {
      lote.fase = "OCR";
    }
    persistir(lote);
    for (Fila fila : objetivo) {
      encolarOcr(lote, fila, principal);
    }
    return lote.snapshot();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus reintentar(UUID batchId, String clientRowId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    Fila fila = fila(lote, clientRowId);
    boolean soloIa;
    synchronized (fila) {
      soloIa =
          "ERROR".equals(fila.iaEstado)
              && "LISTO".equals(fila.ocrEstado)
              && !"ROJO".equals(fila.semaforo);
      if (soloIa) {
        fila.iaEstado = "EN_PROCESO";
        fila.iaResumen = null;
      } else if (fila.expedienteId == null || fila.archivos.isEmpty()) {
        throw ApiException.badRequest("La fila no tiene archivos guardados. No se llama a OCR.");
      } else {
        for (Archivo archivo : fila.archivos) {
          if (!archivo.ocrListo || "ROJO".equals(archivo.semaforo)) {
            archivo.reset();
          }
        }
        fila.ocrEstado = "PROCESANDO";
        fila.semaforo = "GRIS";
        fila.iaEstado = "PENDIENTE";
        fila.iaResumen = null;
        fila.observaciones = null;
      }
    }
    synchronized (lote) {
      lote.fase = soloIa ? "IA" : "OCR";
    }
    persistir(lote);
    if (soloIa) {
      encolarIa(lote, fila, principal);
    } else {
      encolarOcr(lote, fila, principal);
    }
    return lote.snapshot();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus reemplazar(
      UUID batchId, String clientRowId, MultipartFile file, String tipoDocumento, String fileId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    Fila fila = fila(lote, clientRowId);
    if (fila.borradorId == null) {
      throw ApiException.badRequest("La fila no tiene borrador.");
    }
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Adjunta el archivo de reemplazo.");
    }
    String sesion = exigirExpediente(fila);
    String tipo = tipoDe(fila, tipoDocumento);
    DocumentoCargadoDTO doc = carga.subir(sesion, file);
    if (!IngestionMode.FISICO_ESCANEADO.name().equals(fila.ingestionMode)) {
      carga.actualizarTipo(sesion, doc.idDocumento(), new ActualizarTipoRequest(tipo));
    }
    synchronized (fila) {
      if (StringUtils.hasText(fileId)) {
        fila.archivos.removeIf(archivo -> fileId.equals(archivo.fileId));
      }
      fila.archivos.add(Archivo.nuevo(doc.idDocumento(), doc.nombreOriginal(), tipo));
      fila.ocrEstado = "PROCESANDO";
      fila.semaforo = "GRIS";
      fila.iaEstado = "PENDIENTE";
      fila.iaResumen = null;
    }
    synchronized (lote) {
      lote.fase = "OCR";
    }
    persistir(lote);
    encolarOcr(lote, fila, principal);
    return lote.snapshot();
  }

  /** @deprecated El cliente debe usar {@code GET .../stream}. */
  @Deprecated
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public LoteStatus status(UUID batchId) {
    Lote lote = require(batchId);
    reanudar(lote, AuthContext.require());
    return lote.snapshot();
  }

  public SseEmitter stream(UUID batchId) {
    Lote lote = require(batchId);
    reanudar(lote, AuthContext.require());
    SseEmitter emitter = eventos.abrir(batchId);
    try {
      emitter.send(SseEmitter.event().name(nombreEvento(lote)).data(lote.snapshot()));
    } catch (IOException ex) {
      emitter.completeWithError(ex);
    }
    return emitter;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public LoteStatus procesarIa(UUID batchId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    List<Fila> objetivo = new ArrayList<>();
    synchronized (lote) {
      for (Fila fila : lote.filas) {
        if (("VERDE".equals(fila.semaforo) || "NARANJA".equals(fila.semaforo))
            && "LISTO".equals(fila.ocrEstado)
            && !"OK".equals(fila.iaEstado)
            && !"EN_PROCESO".equals(fila.iaEstado)) {
          fila.iaEstado = "EN_PROCESO";
          fila.iaResumen = null;
          objetivo.add(fila);
        }
      }
      if (objetivo.isEmpty()) {
        throw ApiException.badRequest("No hay filas con OCR listo para analizar.");
      }
      lote.fase = "IA";
    }
    persistir(lote);
    for (Fila fila : objetivo) {
      encolarIa(lote, fila, principal);
    }
    return lote.snapshot();
  }

  private Fila crearFila(AuthPrincipal principal, FilaRequest request) {
    String mode = IngestionMode.from(request.ingestionMode()).name();
    String codigo = StringUtils.hasText(request.codigo()) ? request.codigo().trim() : request.productCode();
    Fila fila = new Fila(request.clientRowId().trim(), codigo, request.productCode().trim(), request.canton().trim(), mode);
    try {
      var creado =
          carga.crearBorrador(new CrearBorradorRequest(null, fila.productCode, fila.canton, fila.ingestionMode));
      UUID borradorId = UUID.fromString(creado.id());
      boolean borradorEnBd = borradores.find(borradorId, principal.tenantId()).isPresent();
      LOG.info(
          "ingesta-masiva batch fila={} borradorCreado={} existeEnBd={}",
          fila.clientRowId,
          borradorId,
          borradorEnBd);
      if (!borradorEnBd) {
        throw ApiException.badRequest("El borrador " + borradorId + " no quedó persistido.");
      }
      fila.borradorId = borradorId;
      CaseDetailItem caso =
          promocion.promover(
              new PromoverBorradorRequest(
                  borradorId, codigo, codigo, null, null, null, null, null));
      fila.expedienteId = caso.id();
      fila.codigo = StringUtils.hasText(caso.code()) ? caso.code() : codigo;
      fila.ocrEstado = "PENDIENTE";
      LOG.info(
          "ingesta-masiva batch fila={} borradorCreado={} expedienteCreado={}",
          fila.clientRowId,
          fila.borradorId,
          fila.expedienteId);
    } catch (RuntimeException ex) {
      fila.ocrEstado = "ERROR";
      fila.semaforo = "ROJO";
      fila.observaciones = mensaje(ex);
      LOG.warn("ingesta-masiva fila {} no creada: {}", fila.clientRowId, fila.observaciones);
    }
    return fila;
  }

  private void encolarOcr(Lote lote, Fila fila, AuthPrincipal principal) {
    String llave = lote.id + ":" + fila.clientRowId + ":ocr";
    if (!enVuelo.add(llave)) {
      return;
    }
    publicarJob("OCR_EXTRACTION", lote, fila, principal);
  }

  private void encolarIa(Lote lote, Fila fila, AuthPrincipal principal) {
    String llave = lote.id + ":" + fila.clientRowId + ":ia";
    if (!enVuelo.add(llave)) {
      return;
    }
    publicarJob("IA_ANALYSIS", lote, fila, principal);
  }

  private void publicarJob(String tipo, Lote lote, Fila fila, AuthPrincipal principal) {
    String payload;
    try {
      payload =
          json.writeValueAsString(
              Map.of(
                  "batchId", lote.id.toString(),
                  "clientRowId", fila.clientRowId,
                  "userId", principal.userId().toString(),
                  "membershipId", principal.membershipId() == null ? "" : principal.membershipId().toString(),
                  "sessionId", principal.sessionId() == null ? "" : principal.sessionId().toString()));
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo encolar la fila " + fila.clientRowId + ".");
    }
    Runnable insertar = () -> jobs.encolar(principal.tenantId(), tipo, payload);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              insertar.run();
            }
          });
      return;
    }
    insertar.run();
  }

  public void ejecutarJob(IngestaJobQueue.Job job) {
    com.fasterxml.jackson.databind.JsonNode nodo;
    try {
      nodo = json.readTree(job.payload());
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("Payload de job ilegible.");
    }
    UUID userId = uuid(nodo.path("userId").asText(null));
    UUID sessionId = uuid(nodo.path("sessionId").asText(null));
    UUID membershipId = uuid(nodo.path("membershipId").asText(null));
    AuthContext.set(new AuthPrincipal(userId, sessionId, job.tenantId(), membershipId));
    TenantContext.setTenantId(job.tenantId());
    try {
      UUID batchId = UUID.fromString(nodo.path("batchId").asText());
      String clientRowId = nodo.path("clientRowId").asText();
      if ("OCR_EXTRACTION".equals(job.tipo())) {
        correrOcr(batchId, clientRowId);
      } else if ("IA_ANALYSIS".equals(job.tipo())) {
        correrIa(batchId, clientRowId);
      } else {
        throw IngestaJobQueue.noEsJob(job.tipo());
      }
    } finally {
      String llave = nodo.path("batchId").asText() + ":" + nodo.path("clientRowId").asText();
      enVuelo.remove(llave + ":ocr");
      enVuelo.remove(llave + ":ia");
      AuthContext.clear();
      TenantContext.clear();
    }
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return UUID.fromString(value);
  }

  private void correrOcr(UUID batchId, String clientRowId) {
    Lote lote = require(batchId);
    Fila fila = fila(lote, clientRowId);
    List<Archivo> pendientes;
    String sesion;
    synchronized (fila) {
      sesion = fila.expedienteId == null ? null : fila.expedienteId.toString();
      pendientes = new ArrayList<>();
      for (Archivo archivo : fila.archivos) {
        if (!archivo.ocrListo) {
          pendientes.add(archivo);
        }
      }
    }
    List<CompletableFuture<Void>> trabajos = new ArrayList<>();
    for (Archivo archivo : pendientes) {
      trabajos.add(CompletableFuture.runAsync(() -> leerArchivo(sesion, archivo), ocrExecutor));
    }
    CompletableFuture.allOf(trabajos.toArray(CompletableFuture[]::new)).join();
    List<String> faltantes = documentosFaltantes(fila);
    synchronized (fila) {
      fila.cerrarOcr(faltantes);
    }
    synchronized (lote) {
      lote.refrescarFaseOcr();
    }
    persistir(lote);
  }

  private void leerArchivo(String sesion, Archivo archivo) {
    if (sesion == null) {
      synchronized (archivo) {
        archivo.fallo("Sin expediente. No se llama a OCR.");
      }
      return;
    }
    azure.acquireUninterruptibly();
    try {
      StoredDoc guardado = carga.requireStoredDoc(sesion, archivo.fileId);
      if (guardado.bytes() == null || guardado.bytes().length == 0) {
        synchronized (archivo) {
          archivo.fallo("El archivo no está guardado. No se llama a OCR.");
        }
        return;
      }
      AnalyzeSingleResponse respuesta =
          ocr.analyzeSingle(new AnalyzeSingleRequest(sesion, archivo.fileId, archivo.tipo));
      synchronized (archivo) {
        archivo.aplicar(respuesta);
      }
    } catch (RuntimeException ex) {
      synchronized (archivo) {
        archivo.fallo(mensaje(ex));
      }
    } finally {
      azure.release();
    }
  }

  private void correrIa(UUID batchId, String clientRowId) {
    Lote lote = require(batchId);
    Fila fila = fila(lote, clientRowId);
    try {
      String texto = sanitizer.limpiar(fila.textoConsolidado());
      if (!StringUtils.hasText(texto)) {
        synchronized (fila) {
          fila.iaEstado = "ERROR";
          fila.iaResumen = "Sin texto OCR para analizar.";
        }
      } else if (fila.expedienteId == null || fila.borradorId == null) {
        synchronized (fila) {
          fila.iaEstado = "ERROR";
          fila.iaResumen = "La fila no tiene expediente asociado.";
        }
      } else {
        String sessionId = fila.expedienteId.toString();
        ocr.consolidate(new ConsolidateRequest(sessionId, texto));
        ProcesarExpedienteCompletoResult resultado =
            ia.analizarExpedienteConPromptProducto(
                fila.expedienteId, new AnalysisRequestDTO(Boolean.FALSE, sessionId));
        String resumen = "OK";
        if (resultado.dictamen() != null && StringUtils.hasText(resultado.dictamen().estado())) {
          resumen = resultado.dictamen().estado();
          if (StringUtils.hasText(resultado.dictamen().resumen())) {
            resumen = resumen + " — " + recortar(resultado.dictamen().resumen(), 400);
          }
        }
        var uso = IaConsumoContexto.ultimo();
        synchronized (fila) {
          fila.iaEstado = "OK";
          fila.iaResumen = resumen;
          if (uso != null) {
            fila.modeloIa = uso.modelo();
            fila.tokens = uso.promptTokens() + uso.completionTokens();
            fila.iaMs = uso.ms();
          }
        }
      }
    } catch (RuntimeException ex) {
      synchronized (fila) {
        fila.iaEstado = "ERROR";
        fila.iaResumen = mensaje(ex);
      }
    } finally {
      synchronized (lote) {
        lote.refrescarFaseIa();
      }
      persistir(lote);
    }
  }

  private void persistir(Lote lote) {
    IngestaEstado estado = exportar(lote);
    LoteStatus foto = lote.snapshot();
    String evento = nombreEvento(lote);
    store.guardar(lote.tenantId, lote.id, estado);
    Runnable publicar = () -> eventos.publicar(lote.id, evento, foto);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publicar.run();
            }
          });
      return;
    }
    publicar.run();
  }

  private void reanudar(Lote lote, AuthPrincipal principal) {
    if (!lote.pendienteReanudar) {
      return;
    }
    lote.pendienteReanudar = false;
    for (Fila fila : lote.filas) {
      if ("PROCESANDO".equals(fila.ocrEstado)) {
        encolarOcr(lote, fila, principal);
      } else if ("EN_PROCESO".equals(fila.iaEstado)) {
        encolarIa(lote, fila, principal);
      }
    }
  }

  private static String nombreEvento(Lote lote) {
    boolean corre = false;
    boolean error = false;
    for (Fila fila : lote.filas) {
      if ("PROCESANDO".equals(fila.ocrEstado) || "EN_PROCESO".equals(fila.iaEstado)) {
        corre = true;
      }
      if ("ERROR".equals(fila.ocrEstado) || "ERROR".equals(fila.iaEstado)) {
        error = true;
      }
    }
    if (!corre && "LISTO".equals(lote.fase)) {
      return "BATCH_COMPLETED";
    }
    if (error) {
      return "ITEM_ERROR";
    }
    if (corre) {
      return "ITEM_UPDATED";
    }
    return "BATCH_PROGRESS";
  }

  private IngestaEstado exportar(Lote lote) {
    List<IngestaEstado.Fila> filas = new ArrayList<>();
    for (Fila fila : lote.filas) {
      synchronized (fila) {
        filas.add(fila.exportar());
      }
    }
    return new IngestaEstado(lote.fase, filas);
  }

  private String exigirBorrador(Fila fila, String recibido) {
    UUID esperado = fila.borradorId;
    LOG.info(
        "ingesta-masiva upload fila={} borradorRecibido={} borradorCreado={} expedienteCreado={}",
        fila.clientRowId,
        recibido,
        esperado,
        fila.expedienteId);
    if (esperado == null) {
      throw ApiException.badRequest("La fila no tiene borrador. Vuelve a crear el lote.");
    }
    if (!StringUtils.hasText(recibido) || !esperado.toString().equalsIgnoreCase(recibido.trim())) {
      throw ApiException.badRequest(
          "El id enviado (" + recibido + ") no es el borrador de la fila (" + esperado + ").");
    }
    boolean existe = borradores.find(esperado, AuthContext.require().tenantId()).isPresent();
    LOG.info("ingesta-masiva upload borrador={} existeEnBd={}", esperado, existe);
    if (!existe) {
      throw ApiException.badRequest("El borrador " + esperado + " no existe. No se procesa OCR.");
    }
    return esperado.toString();
  }

  private String exigirExpediente(Fila fila) {
    UUID expedienteId = fila.expedienteId;
    LOG.info(
        "ingesta-masiva upload fila={} expedienteCreado={} borradorCreado={}",
        fila.clientRowId,
        expedienteId,
        fila.borradorId);
    if (expedienteId == null) {
      throw ApiException.badRequest("La fila no tiene expediente. Vuelve a crear el lote.");
    }
    String id = expedienteId.toString();
    boolean existe = casos.context(id) != null;
    LOG.info("ingesta-masiva upload expediente={} existeEnBd={}", expedienteId, existe);
    if (!existe) {
      throw ApiException.badRequest("El expediente " + expedienteId + " no existe. No se procesa OCR.");
    }
    return id;
  }

  private List<String> documentosFaltantes(Fila fila) {
    if (IngestionMode.FISICO_ESCANEADO.name().equals(fila.ingestionMode) || fila.archivos.isEmpty()) {
      return List.of();
    }
    try {
      Set<String> presentes = new LinkedHashSet<>();
      for (Archivo archivo : fila.archivos) {
        if (StringUtils.hasText(archivo.tipo)) {
          presentes.add(archivo.tipo);
        }
      }
      List<String> faltan = new ArrayList<>();
      for (var requisito : productos.detalle(fila.productCode, fila.canton).requisitos()) {
        if (requisito.mandatory()
            && StringUtils.hasText(requisito.documentTypeCode())
            && !presentes.contains(requisito.documentTypeCode())) {
          faltan.add(
              StringUtils.hasText(requisito.description())
                  ? requisito.description()
                  : requisito.documentTypeCode());
        }
      }
      return faltan;
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private Lote require(UUID batchId) {
    AuthPrincipal principal = AuthContext.require();
    Lote cacheado = lotes.get(batchId);
    if (cacheado != null && cacheado.tenantId.equals(principal.tenantId())) {
      return cacheado;
    }
    synchronized (batchId.toString().intern()) {
      cacheado = lotes.get(batchId);
      if (cacheado != null && cacheado.tenantId.equals(principal.tenantId())) {
        return cacheado;
      }
      IngestaEstado estado =
          store
              .cargar(principal.tenantId(), batchId)
              .orElseThrow(() -> ApiException.notFound("Lote de ingesta no encontrado."));
      Lote lote = Lote.restaurar(batchId, principal.tenantId(), estado);
      lotes.put(batchId, lote);
      return lote;
    }
  }

  private static Fila fila(Lote lote, String clientRowId) {
    if (!StringUtils.hasText(clientRowId)) {
      throw ApiException.badRequest("clientRowId es obligatorio.");
    }
    for (Fila fila : lote.filas) {
      if (fila.clientRowId.equals(clientRowId.trim())) {
        return fila;
      }
    }
    throw ApiException.notFound("Fila no encontrada en el lote.");
  }

  private static String tipoDe(Fila fila, String tipo) {
    if (IngestionMode.FISICO_ESCANEADO.name().equals(fila.ingestionMode)) {
      return IngestionMode.EXPEDIENTE_FISICO_ESCANEADO;
    }
    if (!StringUtils.hasText(tipo)) {
      throw ApiException.badRequest("Cada archivo separado necesita un tipo documental.");
    }
    return tipo.trim();
  }

  private static String mensaje(RuntimeException ex) {
    String texto = ex.getMessage();
    if (!StringUtils.hasText(texto)) {
      return ex.getClass().getSimpleName();
    }
    return recortar(texto, 400);
  }

  private static String recortar(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max) + "…";
  }

  private static int porcentaje(Double raw) {
    if (raw == null) {
      return 0;
    }
    double valor = raw <= 1 ? raw * 100 : raw;
    return (int) Math.round(Math.max(0, Math.min(100, valor)));
  }

  private static String semaforoDe(boolean error, boolean legible, int confianza) {
    if (error || !legible || confianza < 70) {
      return "ROJO";
    }
    if (confianza <= 90) {
      return "NARANJA";
    }
    return "VERDE";
  }

  private static String peor(String actual, String siguiente) {
    if ("ROJO".equals(actual) || "ROJO".equals(siguiente)) {
      return "ROJO";
    }
    if ("NARANJA".equals(actual) || "NARANJA".equals(siguiente)) {
      return "NARANJA";
    }
    return "VERDE";
  }

  private static final class Lote {
    private final UUID id;
    private final UUID tenantId;
    private final List<Fila> filas = new ArrayList<>();
    private String fase = "CREADO";
    private boolean pendienteReanudar;

    private Lote(UUID id, UUID tenantId) {
      this.id = id;
      this.tenantId = tenantId;
    }

    private static Lote restaurar(UUID id, UUID tenantId, IngestaEstado estado) {
      Lote lote = new Lote(id, tenantId);
      lote.fase = estado.fase() == null ? "CREADO" : estado.fase();
      lote.pendienteReanudar = true;
      if (estado.filas() != null) {
        for (IngestaEstado.Fila item : estado.filas()) {
          lote.filas.add(Fila.restaurar(item));
        }
      }
      return lote;
    }

    private void refrescarFaseOcr() {
      boolean corriendo = filas.stream().anyMatch(fila -> "PROCESANDO".equals(fila.ocrEstado));
      if (!corriendo && "OCR".equals(fase)) {
        fase = "OCR_LISTO";
      }
    }

    private void refrescarFaseIa() {
      boolean corriendo = filas.stream().anyMatch(fila -> "EN_PROCESO".equals(fila.iaEstado));
      if (!corriendo) {
        fase = "LISTO";
      }
    }

    private boolean cotejoHabilitado() {
      if (filas.isEmpty()) {
        return false;
      }
      for (Fila fila : filas) {
        if (fila.expedienteId == null || fila.archivos.isEmpty() || "PROCESANDO".equals(fila.ocrEstado)) {
          return false;
        }
        if (!"VERDE".equals(fila.semaforo) && !"NARANJA".equals(fila.semaforo)) {
          return false;
        }
      }
      return true;
    }

    private LoteStatus snapshot() {
      int verdes = 0;
      int naranjas = 0;
      int rojos = 0;
      int listas = 0;
      List<FilaStatus> estados = new ArrayList<>();
      for (Fila fila : filas) {
        synchronized (fila) {
          if ("VERDE".equals(fila.semaforo)) {
            verdes++;
            listas++;
          } else if ("NARANJA".equals(fila.semaforo)) {
            naranjas++;
            listas++;
          } else if ("ROJO".equals(fila.semaforo)) {
            rojos++;
          }
          estados.add(fila.status());
        }
      }
      return new LoteStatus(
          id, fase, filas.size(), listas, verdes, naranjas, rojos, cotejoHabilitado(), estados);
    }
  }

  private static final class Fila {
    private final String clientRowId;
    private final String productCode;
    private final String canton;
    private final String ingestionMode;
    private String codigo;
    private UUID borradorId;
    private UUID expedienteId;
    private String ocrEstado = "PENDIENTE";
    private String semaforo = "GRIS";
    private int confianza;
    private String observaciones;
    private String iaEstado = "PENDIENTE";
    private String iaResumen;
    private String modeloIa;
    private Integer tokens;
    private Long iaMs;
    private final List<Archivo> archivos = new ArrayList<>();

    private Fila(String clientRowId, String codigo, String productCode, String canton, String ingestionMode) {
      this.clientRowId = clientRowId;
      this.codigo = codigo;
      this.productCode = productCode;
      this.canton = canton;
      this.ingestionMode = ingestionMode;
    }

    private void cerrarOcr(List<String> faltantes) {
      if (archivos.isEmpty()) {
        ocrEstado = "PENDIENTE";
        semaforo = "GRIS";
        return;
      }
      String color = "VERDE";
      int suma = 0;
      List<String> notas = new ArrayList<>();
      for (Archivo archivo : archivos) {
        color = peor(color, archivo.semaforo);
        suma += archivo.confianza;
        if (StringUtils.hasText(archivo.mensaje)) {
          notas.add(archivo.nombre + ": " + archivo.mensaje);
        }
      }
      if (faltantes != null && !faltantes.isEmpty()) {
        if (!"ROJO".equals(color)) {
          color = "NARANJA";
        }
        notas.add("Falta documento requerido: " + String.join(", ", faltantes));
      }
      ocrEstado = "LISTO";
      semaforo = color;
      confianza = suma / archivos.size();
      observaciones = notas.isEmpty() ? null : recortar(String.join(" · ", notas), 800);
    }

    private IngestaEstado.Fila exportar() {
      List<IngestaEstado.Archivo> items = new ArrayList<>();
      for (Archivo archivo : archivos) {
        items.add(archivo.exportar());
      }
      return new IngestaEstado.Fila(
          clientRowId,
          codigo,
          productCode,
          canton,
          ingestionMode,
          borradorId,
          expedienteId,
          ocrEstado,
          semaforo,
          confianza,
          observaciones,
          iaEstado,
          iaResumen,
          modeloIa,
          tokens,
          iaMs,
          items);
    }

    private static Fila restaurar(IngestaEstado.Fila item) {
      Fila fila = new Fila(item.clientRowId(), item.codigo(), item.productCode(), item.canton(), item.ingestionMode());
      fila.borradorId = item.borradorId();
      fila.expedienteId = item.expedienteId();
      fila.ocrEstado = item.ocrEstado() == null ? "PENDIENTE" : item.ocrEstado();
      fila.semaforo = item.semaforo() == null ? "GRIS" : item.semaforo();
      fila.confianza = item.confianza();
      fila.observaciones = item.observaciones();
      fila.iaEstado = item.iaEstado() == null ? "PENDIENTE" : item.iaEstado();
      fila.iaResumen = item.iaResumen();
      fila.modeloIa = item.modeloIa();
      fila.tokens = item.tokens();
      fila.iaMs = item.iaMs();
      if (item.archivos() != null) {
        for (IngestaEstado.Archivo archivo : item.archivos()) {
          fila.archivos.add(Archivo.restaurar(archivo));
        }
      }
      return fila;
    }

    private String textoConsolidado() {
      StringBuilder texto = new StringBuilder();
      for (Archivo archivo : archivos) {
        if (!StringUtils.hasText(archivo.texto)) {
          continue;
        }
        texto.append("=== DOCUMENTO: ").append(archivo.tipo).append(" ===\n");
        texto.append(archivo.texto.trim()).append("\n\n");
      }
      return texto.toString().trim();
    }

    private FilaStatus status() {
      List<ArchivoStatus> items = new ArrayList<>();
      for (Archivo archivo : archivos) {
        items.add(archivo.status());
      }
      return new FilaStatus(
          clientRowId,
          borradorId,
          expedienteId,
          codigo,
          productCode,
          canton,
          ingestionMode,
          ocrEstado,
          semaforo,
          confianza,
          observaciones,
          iaEstado,
          iaResumen,
          modeloIa,
          tokens,
          iaMs,
          items);
    }
  }

  private static final class Archivo {
    private final String fileId;
    private final String nombre;
    private final String tipo;
    private String semaforo = "GRIS";
    private int confianza;
    private boolean legible;
    private String mensaje;
    private String texto;
    private boolean ocrListo;
    private String origen;
    private Integer ocrMs;

    private Archivo(String fileId, String nombre, String tipo) {
      this.fileId = fileId;
      this.nombre = nombre;
      this.tipo = tipo;
    }

    private static Archivo nuevo(String fileId, String nombre, String tipo) {
      return new Archivo(fileId, nombre, tipo);
    }

    private void aplicar(AnalyzeSingleResponse respuesta) {
      confianza = porcentaje(respuesta.confianza());
      legible = respuesta.legible();
      mensaje = respuesta.mensaje();
      texto = respuesta.textoExtraido();
      origen = respuesta.origen();
      ocrMs = respuesta.duracionMs();
      semaforo = semaforoDe(false, legible, confianza);
      ocrListo = true;
    }

    private void reset() {
      semaforo = "GRIS";
      confianza = 0;
      legible = false;
      mensaje = null;
      texto = null;
      ocrListo = false;
    }

    private void fallo(String error) {
      legible = false;
      confianza = 0;
      mensaje = error;
      semaforo = "ROJO";
      ocrListo = true;
    }

    private IngestaEstado.Archivo exportar() {
      return new IngestaEstado.Archivo(fileId, nombre, tipo, semaforo, confianza, legible, mensaje, texto, ocrListo, origen, ocrMs);
    }

    private static Archivo restaurar(IngestaEstado.Archivo item) {
      Archivo archivo = new Archivo(item.fileId(), item.nombre(), item.tipo());
      archivo.semaforo = item.semaforo() == null ? "GRIS" : item.semaforo();
      archivo.confianza = item.confianza();
      archivo.legible = item.legible();
      archivo.mensaje = item.mensaje();
      archivo.texto = item.texto();
      archivo.ocrListo = item.ocrListo();
      archivo.origen = item.origen();
      archivo.ocrMs = item.ocrMs();
      return archivo;
    }

    private ArchivoStatus status() {
      return new ArchivoStatus(
          fileId, nombre, tipo, semaforo, confianza, legible, mensaje, recortar(texto, TEXTO_PREVIEW), origen, ocrMs);
    }
  }
}
