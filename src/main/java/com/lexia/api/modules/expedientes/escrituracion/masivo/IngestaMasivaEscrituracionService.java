package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.PromoverBorradorRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.IngestionMode;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.CrearBorradorRequest;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisService;
import com.lexia.api.modules.expedientes.escrituracion.core.ProcesarExpedienteCompletoResult;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ArchivoStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.AsignacionArchivo;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.AsignacionesPayload;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.CrearLoteRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.FilaRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.FilaStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaJobWorker.ProcesarDocumento;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LoteStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ResumenLote;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import com.lexia.api.modules.tenancy.TenantContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
  private final JobRepository jobs;
  private final DocumentoTextoOcrRepository textoOcr;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;
  private final TransactionTemplate tx;
  private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> streams = new ConcurrentHashMap<>();

  public IngestaMasivaEscrituracionService(
      CargaDocumentoService carga,
      CaseDocumentoStore casos,
      ExpedienteBorradorStore borradores,
      BorradorPromocionService promocion,
      OcrAzureBatchService ocr,
      IaAnalysisService ia,
      ProductoBiessService productos,
      JobRepository jobs,
      DocumentoTextoOcrRepository textoOcr,
      ObjectMapper objectMapper,
      EntityManager entityManager,
      PlatformTransactionManager transactionManager) {
    this.carga = carga;
    this.casos = casos;
    this.borradores = borradores;
    this.promocion = promocion;
    this.ocr = ocr;
    this.ia = ia;
    this.productos = productos;
    this.jobs = jobs;
    this.textoOcr = textoOcr;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
    this.tx = new TransactionTemplate(transactionManager);
  }

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
    persistir(lote);
    LOG.info("ingesta-masiva lote={} filas={} tenant={}", lote.id, lote.filas.size(), principal.tenantId());
    return lote.snapshot();
  }

  public LoteStatus subir(UUID batchId, AsignacionesPayload payload, List<MultipartFile> files) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
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
      Archivo archivo = Archivo.nuevo(doc.idDocumento(), doc.nombreOriginal(), tipo);
      synchronized (fila) {
        fila.archivos.add(archivo);
        fila.ocrEstado = "PROCESANDO";
        fila.semaforo = "GRIS";
        fila.iaEstado = "PENDIENTE";
        fila.observaciones = null;
      }
      encolarProcesarDocumento(lote, fila, archivo, principal);
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
    for (Fila fila : objetivo) {
      for (Archivo archivo : fila.archivos) {
        if (!archivo.ocrListo) {
          encolarProcesarDocumento(lote, fila, archivo, principal);
        }
      }
    }
    persistir(lote);
    return lote.snapshot();
  }

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
        fila.inicioMs = 0;
        fila.duracionMs = 0;
      }
    }
    synchronized (lote) {
      lote.fase = soloIa ? "IA" : "OCR";
    }
    for (Archivo archivo : fila.archivos) {
      if (soloIa || !archivo.ocrListo || "ROJO".equals(archivo.semaforo)) {
        encolarProcesarDocumento(lote, fila, archivo, principal);
      }
    }
    persistir(lote);
    return lote.snapshot();
  }

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
    carga.borrarOcr(sesion, "consolidado");
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
    Archivo ultimo = fila.archivos.get(fila.archivos.size() - 1);
    encolarProcesarDocumento(lote, fila, ultimo, principal);
    persistir(lote);
    return lote.snapshot();
  }

  public LoteStatus status(UUID batchId) {
    return require(batchId).snapshot();
  }

  public LoteStatus procesarIa(UUID batchId) {
    return require(batchId).snapshot();
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

  public SseEmitter stream(UUID batchId) {
    require(batchId);
    SseEmitter emitter = new SseEmitter(0L);
    streams.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    Runnable drop = () -> streams.getOrDefault(batchId, new CopyOnWriteArrayList<>()).remove(emitter);
    emitter.onCompletion(drop);
    emitter.onTimeout(drop);
    emitter.onError(err -> drop.run());
    return emitter;
  }

  public ResumenLote resumen(UUID batchId) {
    require(batchId);
    int total = contar(batchId, null);
    int completados = contar(batchId, "COMPLETADO");
    int fallidos = contar(batchId, "ERROR");
    int enProceso = contarEnProceso(batchId);
    return new ResumenLote(batchId, total, completados, enProceso, fallidos, 0);
  }

  /** Reintento manual de una fila: borra el consolidado y reencola sus archivos. */
  public LoteStatus reintentarItem(UUID batchId, String itemId) {
    Fila fila = fila(require(batchId), itemId);
    if (fila.expedienteId != null) {
      carga.borrarOcr(fila.expedienteId.toString(), "consolidado");
    }
    return reintentar(batchId, itemId);
  }

  private void encolarProcesarDocumento(Lote lote, Fila fila, Archivo archivo, AuthPrincipal principal) {
    synchronized (fila) {
      if (fila.inicioMs == 0) {
        fila.inicioMs = System.currentTimeMillis();
      }
    }
    ProcesarDocumento payload =
        new ProcesarDocumento(
            lote.id,
            fila.clientRowId,
            fila.expedienteId,
            archivo.fileId,
            "expedientes/" + fila.expedienteId + "/" + archivo.fileId,
            principal.tenantId(),
            principal.userId(),
            archivo.tipo);
    try {
      jobs.encolar(principal.tenantId(), "PROCESAR_DOCUMENTO", objectMapper.writeValueAsString(payload));
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo encolar el documento.");
    }
    LOG.info("job PROCESAR_DOCUMENTO {}", payload);
  }

  public void procesarDocumento(ProcesarDocumento payload) {
    Lote lote = cargar(payload.tenantId(), payload.loteId());
    Fila fila = lote == null ? null : filaONull(lote, payload.itemId());
    Archivo archivo = fila == null ? null : archivoDe(fila, payload.docId());
    try {
      if (payload.expedienteId() == null) {
        throw ApiException.badRequest("Sin expediente. No se llama a OCR.");
      }
      String sesion = payload.expedienteId().toString();
      StoredDoc guardado = carga.requireStoredDoc(sesion, payload.docId());
      if (guardado.bytes() == null || guardado.bytes().length == 0) {
        throw ApiException.badRequest("El archivo no está guardado. No se llama a OCR.");
      }
      String texto = ocr.extraerTexto(guardado.bytes(), guardado.mimeType());
      String tipo = archivo == null ? payload.tipoDocumento() : archivo.tipo;
      carga.guardarResultadoOcr(
          sesion,
          new DocumentoOcrResultadoDTO(
              payload.docId(), guardado.nombreOriginal(), tipo, texto, null, "LEGIBLE", null, null));
      if (archivo != null) {
        synchronized (archivo) {
          archivo.texto = texto;
          archivo.legible = StringUtils.hasText(texto);
          archivo.ocrListo = true;
          archivo.semaforo = archivo.legible ? "VERDE" : "NARANJA";
        }
      }
      if (!esUltimoArchivo(fila, payload.expedienteId())) {
        if (lote != null) {
          persistir(lote);
          emitir(lote.id, "DOCUMENTO_LISTO", lote.snapshot());
        }
        return;
      }
      String unido = textoConsolidado(sesion);
      carga.guardarResultadoOcr(
          sesion,
          new DocumentoOcrResultadoDTO(
              "consolidado", "consolidado", "CONSOLIDADO", unido, null, "LEGIBLE", null, null));
      ProcesarExpedienteCompletoResult resultado =
          ia.analizarExpedienteConPromptProducto(
              payload.expedienteId(), new AnalysisRequestDTO(Boolean.TRUE, sesion));
      String resumen = "OK";
      if (resultado.dictamen() != null && StringUtils.hasText(resultado.dictamen().estado())) {
        resumen = resultado.dictamen().estado();
      }
      if (fila != null) {
        synchronized (fila) {
          fila.cerrarOcr(List.of());
          fila.iaEstado = "COMPLETADO";
          fila.iaResumen = resumen;
          fila.ocrEstado = "COMPLETADO";
          fila.duracionMs = Math.max(0, System.currentTimeMillis() - fila.inicioMs);
        }
      }
      if (lote != null) {
        persistir(lote);
        emitir(lote.id, "DOCUMENTO_LISTO", lote.snapshot());
      }
    } catch (RuntimeException ex) {
      String detalle = mensaje(ex);
      LOG.error("job PROCESAR_DOCUMENTO ERROR payload={} error_log={}", payload, detalle);
      if (fila != null) {
        synchronized (fila) {
          if (archivo != null) {
            archivo.fallo(detalle);
          }
          fila.ocrEstado = "ERROR";
          fila.iaEstado = "ERROR";
          fila.semaforo = "ROJO";
          fila.iaResumen = detalle;
          fila.observaciones = detalle;
        }
      }
      if (lote != null) {
        persistir(lote);
        emitir(lote.id, "ERROR", lote.snapshot());
      }
      throw ex;
    }
  }

  private boolean esUltimoArchivo(Fila fila, UUID expedienteId) {
    if (fila == null) {
      return expedienteId == null || jobs.pendientesDelExpediente(expedienteId) == 0;
    }
    synchronized (fila) {
      for (Archivo archivo : fila.archivos) {
        if (!archivo.ocrListo) {
          return false;
        }
      }
    }
    return true;
  }

  private String textoConsolidado(String sesion) {
    StringBuilder sb = new StringBuilder();
    for (DocumentoTextoOcr row : textoOcr.findByIdExpedienteOrderByCreatedAtAsc(sesion)) {
      if ("consolidado".equals(row.getIdDocumento()) || !StringUtils.hasText(row.getTextoOcr())) {
        continue;
      }
      String tipo = StringUtils.hasText(row.getTipoDocumento()) ? row.getTipoDocumento() : row.getIdDocumento();
      sb.append("=== DOCUMENTO: ").append(tipo).append(" ===\n");
      sb.append(row.getTextoOcr().trim()).append("\n\n");
    }
    return sb.toString().trim();
  }

  private Fila filaONull(Lote lote, String clientRowId) {
    for (Fila fila : lote.filas) {
      if (fila.clientRowId.equals(clientRowId)) {
        return fila;
      }
    }
    return null;
  }

  private Archivo archivoDe(Fila fila, String docId) {
    for (Archivo archivo : fila.archivos) {
      if (archivo.fileId.equals(docId)) {
        return archivo;
      }
    }
    return null;
  }

  private void emitir(UUID loteId, String evento, LoteStatus status) {
    for (SseEmitter emitter : streams.getOrDefault(loteId, new CopyOnWriteArrayList<>())) {
      try {
        emitter.send(SseEmitter.event().name(evento).data(status));
      } catch (IOException ex) {
        emitter.complete();
      }
    }
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
    Lote lote = cargar(principal.tenantId(), batchId);
    if (lote == null) {
      throw ApiException.notFound("Lote de ingesta no encontrado.");
    }
    return lote;
  }

  private int contar(UUID loteId, String estado) {
    String sql =
        estado == null
            ? "SELECT count(*)::int FROM app.ingesta_item WHERE lote_id = :lote"
            : "SELECT count(*)::int FROM app.ingesta_item WHERE lote_id = :lote AND estado = :estado";
    var q = entityManager.createNativeQuery(sql).setParameter("lote", loteId);
    if (estado != null) {
      q.setParameter("estado", estado);
    }
    return ((Number) q.getSingleResult()).intValue();
  }

  private int contarEnProceso(UUID loteId) {
    return ((Number)
            entityManager
                .createNativeQuery(
                    """
                    SELECT count(*)::int FROM app.ingesta_item
                    WHERE lote_id = :lote AND estado IN ('PENDIENTE', 'EN_PROCESO')
                    """)
                .setParameter("lote", loteId)
                .getSingleResult())
        .intValue();
  }

  private void persistir(Lote lote) {
    try {
      persistirEnBd(lote);
    } catch (Exception e) {
      LOG.error("Error persistiendo lote: {}", e.getMessage(), e);
      throw e;
    }
  }

  /** Misma conexión de la transacción JPA: SET LOCAL y luego los INSERT. */
  private void persistirEnBd(Lote lote) {
    UUID tenant = lote.tenantId != null ? lote.tenantId : TenantContext.getTenantId();
    int hechas = 0;
    for (Fila fila : lote.filas) {
      if ("COMPLETADO".equals(estadoItem(fila))) {
        hechas++;
      }
    }
    int completadas = hechas;
    tx.executeWithoutResult(
        status ->
            entityManager.unwrap(Session.class).doWork(connection -> escribirLote(connection, lote, tenant, completadas)));
  }

  private void escribirLote(Connection connection, Lote lote, UUID tenant, int hechas) throws java.sql.SQLException {
    try (PreparedStatement set =
        connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
      set.setString(1, tenant == null ? "" : tenant.toString());
      set.execute();
    }
    try (PreparedStatement upsert =
        connection.prepareStatement(
            """
            INSERT INTO app.ingesta_lote (id, tenant_id, estado, total_filas, filas_completadas)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              estado = EXCLUDED.estado,
              total_filas = EXCLUDED.total_filas,
              filas_completadas = EXCLUDED.filas_completadas
            """)) {
      upsert.setObject(1, lote.id);
      upsert.setObject(2, tenant);
      upsert.setString(3, lote.fase);
      upsert.setInt(4, lote.filas.size());
      upsert.setInt(5, hechas);
      upsert.executeUpdate();
    }
    try (PreparedStatement delete =
        connection.prepareStatement(
            "DELETE FROM app.ingesta_item WHERE lote_id = ? AND tenant_id = ?")) {
      delete.setObject(1, lote.id);
      delete.setObject(2, tenant);
      delete.executeUpdate();
    }
    int orden = 0;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO app.ingesta_item
              (id, tenant_id, lote_id, client_row_id, orden, expediente_id, borrador_id,
               estado, porcentaje_progreso, error_log, detalle)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """)) {
      for (Fila fila : lote.filas) {
        String estado = estadoItem(fila);
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, tenant);
        insert.setObject(3, lote.id);
        insert.setString(4, fila.clientRowId);
        insert.setInt(5, orden++);
        insert.setObject(6, fila.expedienteId);
        insert.setObject(7, fila.borradorId);
        insert.setString(8, estado);
        insert.setInt(9, "COMPLETADO".equals(estado) ? 100 : 0);
        insert.setString(10, "ERROR".equals(estado) ? fila.observaciones : null);
        insert.setString(11, json(fila));
        insert.executeUpdate();
      }
    }
  }

  private Lote cargar(UUID tenantId, UUID loteId) {
    List<?> fase =
        entityManager
            .createNativeQuery(
                "SELECT estado FROM app.ingesta_lote WHERE id = :id AND tenant_id = :tenant")
            .setParameter("id", loteId)
            .setParameter("tenant", tenantId)
            .getResultList();
    if (fase.isEmpty()) {
      return null;
    }
    Lote lote = new Lote(loteId, tenantId);
    lote.fase = String.valueOf(fase.get(0));
    List<?> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT CAST(detalle AS text) FROM app.ingesta_item
                WHERE lote_id = :id AND tenant_id = :tenant
                ORDER BY orden
                """)
            .setParameter("id", loteId)
            .setParameter("tenant", tenantId)
            .getResultList();
    for (Object row : rows) {
      lote.filas.add(leerFila(String.valueOf(row)));
    }
    return lote;
  }

  private static String estadoItem(Fila fila) {
    if ("ERROR".equals(fila.ocrEstado) || "ERROR".equals(fila.iaEstado)) {
      return "ERROR";
    }
    if ("COMPLETADO".equals(fila.ocrEstado) || "COMPLETADO".equals(fila.iaEstado)) {
      return "COMPLETADO";
    }
    if ("PROCESANDO".equals(fila.ocrEstado) || "EN_PROCESO".equals(fila.iaEstado)) {
      return "EN_PROCESO";
    }
    return "PENDIENTE";
  }

  private String json(Fila fila) {
    try {
      ObjectMapper copia = objectMapper.copy();
      copia.setVisibility(
          copia
              .getSerializationConfig()
              .getDefaultVisibilityChecker()
              .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
      return copia.writeValueAsString(fila);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo guardar la fila " + fila.clientRowId + ".");
    }
  }

  private Fila leerFila(String json) {
    try {
      ObjectMapper copia = objectMapper.copy();
      copia.setVisibility(
          copia
              .getDeserializationConfig()
              .getDefaultVisibilityChecker()
              .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
      return copia.readValue(json, Fila.class);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("El lote guardado no se puede leer.");
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

    private Lote(UUID id, UUID tenantId) {
      this.id = id;
      this.tenantId = tenantId;
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
    private String clientRowId;
    private String productCode;
    private String canton;
    private String ingestionMode;
    private String codigo;
    private UUID borradorId;
    private UUID expedienteId;
    private String ocrEstado = "PENDIENTE";
    private String semaforo = "GRIS";
    private int confianza;
    private String observaciones;
    private String iaEstado = "PENDIENTE";
    private String iaResumen;
    private long inicioMs;
    private long duracionMs;
    private final List<Archivo> archivos = new ArrayList<>();

    private Fila() {
      this.clientRowId = null;
      this.productCode = null;
      this.canton = null;
      this.ingestionMode = null;
    }

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
          items);
    }
  }

  private static final class Archivo {
    private String fileId;
    private String nombre;
    private String tipo;
    private String semaforo = "GRIS";
    private int confianza;
    private boolean legible;
    private String mensaje;
    @JsonIgnore private String texto;
    private boolean ocrListo;

    private Archivo() {
      this.fileId = null;
      this.nombre = null;
      this.tipo = null;
    }

    private Archivo(String fileId, String nombre, String tipo) {
      this.fileId = fileId;
      this.nombre = nombre;
      this.tipo = tipo;
    }

    private static Archivo nuevo(String fileId, String nombre, String tipo) {
      return new Archivo(fileId, nombre, tipo);
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

    private ArchivoStatus status() {
      return new ArchivoStatus(
          fileId, nombre, tipo, semaforo, confianza, legible, mensaje, recortar(texto, TEXTO_PREVIEW));
    }
  }
}
