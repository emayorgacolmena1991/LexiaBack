package com.lexia.api.modules.expedientes.escrituracion.masivo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.hibernate.Session;
import org.springframework.http.HttpStatus;
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
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ExpedienteEnLoteDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.FilaRequest;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.FilaStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaJobWorker.ProcesarDocumento;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaJobWorker.ProcesarIaExpediente;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LoteListItemDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LoteStatus;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.LotesPageDto;
import com.lexia.api.modules.expedientes.escrituracion.masivo.IngestaMasivaDtos.ResumenLote;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.ia.ocr.LayoutExtractResult;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import com.lexia.api.modules.tenancy.TenantContext;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
    lote.producto = valorComun(request.filas().stream().map(FilaRequest::productCode).toList());
    lote.canton = valorComun(request.filas().stream().map(FilaRequest::canton).toList());
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
    LOG.info("Iniciando carga de archivos. Total: {}", binarios.size());
    for (int i = 0; i < asignaciones.size(); i++) {
      AsignacionArchivo asignacion = asignaciones.get(i);
      MultipartFile file = binarios.get(i);
      Fila fila = fila(lote, asignacion.clientRowId());
      if (file == null || file.isEmpty()) {
        LOG.warn(
            "Archivo vacío ignorado en fila {} nombre={}",
            fila.clientRowId,
            file != null ? file.getOriginalFilename() : null);
        synchronized (fila) {
          fila.ocrEstado = "ERROR";
          fila.semaforo = "ROJO";
          fila.observaciones = "Archivo corrupto o vacío. Reemplazar.";
        }
        if (!tocadas.contains(fila)) {
          tocadas.add(fila);
        }
        continue;
      }
      try {
        exigirBorrador(fila, asignacion.borradorId());
        String sesion = exigirExpediente(fila);
        String tipo = tipoDe(fila, asignacion.tipoDocumento());
        DocumentoCargadoDTO doc = carga.subir(sesion, file);
        if (!IngestionMode.FISICO_ESCANEADO.name().equals(fila.ingestionMode)) {
          carga.actualizarTipo(sesion, doc.idDocumento(), new ActualizarTipoRequest(tipo));
        }
        Archivo archivo = Archivo.nuevo(doc.idDocumento(), doc.nombreOriginal(), tipo);
        synchronized (fila) {
          fila.archivos.add(archivo);
          if (!"ERROR".equals(fila.ocrEstado)) {
            fila.ocrEstado = "PENDIENTE";
            fila.semaforo = "GRIS";
            fila.iaEstado = "PENDIENTE";
            fila.observaciones = null;
          }
        }
      } catch (Exception e) {
        String detalle =
            e instanceof RuntimeException re ? mensaje(re) : e.getMessage();
        if (!StringUtils.hasText(detalle)) {
          detalle = e.getClass().getSimpleName();
        }
        LOG.error("Fallo al procesar archivo en fila {}", fila.clientRowId, e);
        String nombre =
            file != null && StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "archivo";
        String tipoFallo =
            StringUtils.hasText(asignacion.tipoDocumento())
                ? asignacion.tipoDocumento().trim()
                : "DOCUMENTO";
        synchronized (fila) {
          Archivo fallido = Archivo.nuevo("upload-error-" + UUID.randomUUID(), nombre, tipoFallo);
          fallido.fallo(detalle);
          fila.archivos.add(fallido);
          fila.ocrEstado = "ERROR";
          fila.semaforo = "ROJO";
          fila.iaEstado = "ERROR";
          fila.observaciones = detalle;
        }
      }
      if (!tocadas.contains(fila)) {
        tocadas.add(fila);
      }
    }
    if (!tocadas.isEmpty()) {
      synchronized (lote) {
        lote.fase = "ARCHIVOS";
        if (!StringUtils.hasText(lote.producto)) {
          Fila primera = tocadas.get(0);
          lote.producto = primera.productCode;
          lote.canton = primera.canton;
        }
      }
      UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
      for (Fila tocada : tocadas) {
        actualizarItemEnBd(lote.id, tenant, tocada, false);
      }
      actualizarContadoresLote(lote.id, tenant, lote.fase);
      emitirLoteActualizado(tenant, lote.id);
    }
    return cargar(principal.tenantId(), batchId).snapshot();
  }

  public LoteStatus ejecutarOcr(UUID batchId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    synchronized (lote) {
      lote.fase = "OCR";
    }
    int encolados = 0;
    for (Fila fila : lote.filas) {
      List<Archivo> pendientes;
      synchronized (fila) {
        if (!"PENDIENTE".equals(fila.ocrEstado)) {
          continue;
        }
        if (fila.expedienteId == null) {
          continue;
        }
        pendientes = new ArrayList<>();
        for (Archivo archivo : fila.archivos) {
          if (esFalloSubida(archivo)) {
            continue;
          }
          if (!archivo.ocrListo) {
            pendientes.add(archivo);
          }
        }
        if (pendientes.isEmpty()) {
          continue;
        }
        fila.ocrEstado = "PROCESANDO";
        fila.semaforo = "GRIS";
        fila.observaciones = null;
      }
      actualizarItemEnBd(lote.id, tenant, fila, false);
      for (Archivo archivo : pendientes) {
        encolarProcesarDocumento(lote, fila, archivo, principal);
        encolados++;
      }
    }
    actualizarContadoresLote(lote.id, tenant, lote.fase);
    LOG.info("ejecutar-ocr batch={} encolados={}", batchId, encolados);
    return cargar(principal.tenantId(), batchId).snapshot();
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
        fila.archivos.removeIf(archivo -> esFalloSubida(archivo));
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
      if (esFalloSubida(archivo)) {
        continue;
      }
      if (soloIa || !archivo.ocrListo || "ROJO".equals(archivo.semaforo)) {
        encolarProcesarDocumento(lote, fila, archivo, principal);
      }
    }
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    actualizarItemEnBd(lote.id, tenant, fila, false);
    actualizarContadoresLote(lote.id, tenant, lote.fase);
    return cargar(principal.tenantId(), batchId).snapshot();
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
      fila.archivos.removeIf(archivo -> esFalloSubida(archivo));
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
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    actualizarItemEnBd(lote.id, tenant, fila, false);
    actualizarContadoresLote(lote.id, tenant, lote.fase);
    return cargar(principal.tenantId(), batchId).snapshot();
  }

  public LoteStatus status(UUID batchId) {
    return require(batchId).snapshot();
  }

  public LotesPageDto listarLotes(
      int page, int size, String status, String producto, String canton, String q) {
    AuthPrincipal principal = AuthContext.require();
    int pagina = Math.max(page, 0);
    int tamano = Math.min(Math.max(size, 1), 100);
    String statusFiltro = textoFiltro(status);
    String productoFiltro = textoFiltro(producto);
    String cantonFiltro = textoFiltro(canton);
    String busqueda = textoFiltro(q);
    List<LoteListItemDto> items = new ArrayList<>();
    for (Object row :
        consultaLotes(
                false,
                principal.tenantId(),
                productoFiltro,
                cantonFiltro,
                busqueda,
                statusFiltro,
                tamano,
                pagina * tamano)
            .getResultList()) {
      items.add(mapLoteListItem((Object[]) row));
    }
    Number total =
        (Number)
            consultaLotes(
                    true,
                    principal.tenantId(),
                    productoFiltro,
                    cantonFiltro,
                    busqueda,
                    statusFiltro,
                    tamano,
                    0)
                .getSingleResult();
    return new LotesPageDto(items, total.longValue(), pagina, tamano);
  }

  public LoteListItemDto detalleLote(UUID batchId) {
    AuthPrincipal principal = AuthContext.require();
    List<?> rows =
        entityManager
            .createNativeQuery(sqlLoteDetalle())
            .setParameter("tenant", principal.tenantId())
            .setParameter("batchId", batchId)
            .getResultList();
    if (rows.isEmpty()) {
      throw ApiException.notFound("Lote de ingesta no encontrado.");
    }
    return mapLoteListItem((Object[]) rows.get(0));
  }

  public List<ExpedienteEnLoteDto> listarExpedientesLote(UUID batchId) {
    Lote lote = require(batchId);
    Map<String, List<Fila>> porExpediente =
        lote.filas.stream()
            .filter(f -> f.expedienteId != null)
            .collect(Collectors.groupingBy(f -> f.expedienteId.toString()));

    return porExpediente.entrySet().stream()
        .map(
            e -> {
              List<Fila> filas = e.getValue();
              Fila first = filas.get(0);

              String sem =
                  filas.stream()
                      .flatMap(f -> f.archivos.stream())
                      .map(a -> a.semaforo)
                      .reduce("GRIS", IngestaMasivaEscrituracionService::peor);

              String obs =
                  filas.stream()
                      .flatMap(f -> f.archivos.stream())
                      .filter(a -> a.ocrListo && !a.legible)
                      .findFirst()
                      .map(a -> "Documento ilegible: " + a.nombre)
                      .orElse(
                          filas.stream()
                              .filter(f -> "ERROR".equals(f.ocrEstado))
                              .findFirst()
                              .map(f -> f.observaciones)
                              .orElse(null));

              int totalArch = filas.stream().mapToInt(f -> f.archivos.size()).sum();
              int legibles =
                  (int)
                      filas.stream()
                          .flatMap(f -> f.archivos.stream())
                          .filter(a -> a.legible)
                          .count();

              String codigo =
                  StringUtils.hasText(first.codigo)
                      ? first.codigo
                      : shortUuid(first.expedienteId);

              return new ExpedienteEnLoteDto(
                  e.getKey(),
                  first.clientRowId,
                  codigo,
                  first.cliente,
                  first.clienteIdentificacion,
                  first.productCode,
                  first.canton,
                  totalArch,
                  legibles,
                  sem,
                  obs,
                  first.ocrEstado,
                  first.iaEstado,
                  ultimaActualizacion(filas));
            })
        .sorted(
            Comparator.comparing(
                ExpedienteEnLoteDto::ultimaActualizacion,
                Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  public LoteStatus procesarIa(UUID batchId) {
    Lote lote = require(batchId);
    AuthPrincipal principal = AuthContext.require();
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    int encolados = 0;
    List<?> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT i.client_row_id, i.expediente_id, CAST(i.detalle AS text)
                FROM app.ingesta_item i
                WHERE i.lote_id = :lote AND i.tenant_id = :tenant
                """)
            .setParameter("lote", batchId)
            .setParameter("tenant", tenant)
            .getResultList();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      String itemId = String.valueOf(cols[0]);
      UUID expedienteId = uuidONull(cols[1]);
      Fila fila = leerFila(String.valueOf(cols[2]));
      String motivo = motivoSkipIa(fila, expedienteId);
      if (motivo != null) {
        LOG.info("skip item={} motivo={}", itemId, motivo);
        continue;
      }
      fila.clientRowId = itemId;
      fila.expedienteId = expedienteId;
      synchronized (fila) {
        fila.iaEstado = "PENDIENTE";
      }
      actualizarItemEnBd(lote.id, tenant, fila, false);
      encolarProcesarIa(lote, fila, principal);
      LOG.info("encolar PROCESAR_IA_EXPEDIENTE item={} expediente={}", itemId, expedienteId);
      encolados++;
    }
    LOG.info("procesarIa batch={} encolados={}", batchId, encolados);
    LoteStatus snap = cargar(principal.tenantId(), batchId).snapshot();
    return new LoteStatus(
        snap.batchId(),
        snap.fase(),
        snap.total(),
        snap.listasOcr(),
        snap.verdes(),
        snap.naranjas(),
        snap.rojos(),
        snap.cotejoHabilitado(),
        snap.filas(),
        encolados);
  }

  public LoteStatus overrideExpediente(UUID batchId, UUID expedienteId, String motivo) {
    if (motivo == null || motivo.trim().length() < 15) {
      throw new IllegalArgumentException("El motivo debe tener al menos 15 caracteres.");
    }
    AuthPrincipal principal = AuthContext.require();
    Lote lote = require(batchId);
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    String justificacion = motivo.trim();
    int[] actualizadas = new int[1];
    tx.executeWithoutResult(
        status ->
            entityManager
                .unwrap(Session.class)
                .doWork(
                    connection -> {
                      aplicarTenant(connection, tenant);
                      List<String[]> antes = new ArrayList<>();
                      try (PreparedStatement select =
                          connection.prepareStatement(
                              """
                              SELECT client_row_id,
                                     COALESCE(detalle->>'semaforo', 'GRIS'),
                                     detalle->>'observaciones'
                              FROM app.ingesta_item
                              WHERE lote_id = ? AND expediente_id = ? AND tenant_id = ?
                              """)) {
                        select.setObject(1, batchId);
                        select.setObject(2, expedienteId);
                        select.setObject(3, tenant);
                        try (ResultSet rs = select.executeQuery()) {
                          while (rs.next()) {
                            antes.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
                          }
                        }
                      }
                      try (PreparedStatement update =
                          connection.prepareStatement(
                              """
                              UPDATE app.ingesta_item
                              SET estado = 'COMPLETADO',
                                  detalle = jsonb_set(
                                      jsonb_set(
                                          jsonb_set(detalle, '{ocrEstado}', '"COMPLETADO"'),
                                          '{iaEstado}', '"PENDIENTE"'
                                      ),
                                      '{semaforo}', '"VERDE"'
                                  )
                              WHERE lote_id = ? AND expediente_id = ? AND tenant_id = ?
                              """)) {
                        update.setObject(1, batchId);
                        update.setObject(2, expedienteId);
                        update.setObject(3, tenant);
                        actualizadas[0] = update.executeUpdate();
                      }
                      try (PreparedStatement insert =
                          connection.prepareStatement(
                              """
                              INSERT INTO app.ingesta_override
                                (id, tenant_id, lote_id, expediente_id, item_id, justificacion,
                                 aprobado_por, semaforo_antes, observacion_antes)
                              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                              """)) {
                        for (String[] row : antes) {
                          UUID itemId;
                          try {
                            itemId = UUID.fromString(row[0]);
                          } catch (IllegalArgumentException ex) {
                            LOG.warn("override sin auditoría item={} no es UUID", row[0]);
                            continue;
                          }
                          String semaforo = row[1] == null ? "GRIS" : row[1];
                          if (semaforo.length() > 16) {
                            semaforo = semaforo.substring(0, 16);
                          }
                          insert.setObject(1, UUID.randomUUID());
                          insert.setObject(2, tenant);
                          insert.setObject(3, batchId);
                          insert.setObject(4, expedienteId);
                          insert.setObject(5, itemId);
                          insert.setString(6, justificacion);
                          insert.setObject(7, principal.userId());
                          insert.setString(8, semaforo);
                          insert.setString(9, row[2]);
                          insert.addBatch();
                        }
                        insert.executeBatch();
                      }
                    }));
    if (actualizadas[0] == 0) {
      throw ApiException.notFound("Expediente no encontrado en el lote.");
    }
    emitirLoteActualizado(tenant, batchId);
    Lote fresh = cargar(tenant, batchId);
    if (fresh == null) {
      throw ApiException.notFound("Lote de ingesta no encontrado.");
    }
    return fresh.snapshot();
  }

  public LoteStatus confirmarLote(UUID batchId, String confirmadoPor) {
    AuthPrincipal principal = AuthContext.require();
    Lote lote = require(batchId);
    UUID tenant = lote.tenantId != null ? lote.tenantId : principal.tenantId();
    UUID usuario = usuarioConfirmacion(confirmadoPor, principal.userId());
    tx.executeWithoutResult(
        status ->
            entityManager
                .unwrap(Session.class)
                .doWork(
                    connection -> {
                      aplicarTenant(connection, tenant);
                      int filas = contarItems(connection, batchId, tenant);
                      if (filas == 0) {
                        throw ApiException.badRequest("El lote no tiene filas.");
                      }
                      if (contarRojos(connection, batchId, tenant) > 0) {
                        throw new ApiException(
                            HttpStatus.CONFLICT,
                            "COA_LOTE_CON_ROJOS",
                            "No se puede confirmar un lote con archivos en rojo");
                      }
                      try (PreparedStatement update =
                          connection.prepareStatement(
                              """
                              UPDATE app.ingesta_lote
                              SET status_revision = 'CONFIRMADO', confirmado_por = ?, confirmado_at = NOW()
                              WHERE id = ? AND tenant_id = ?
                              """)) {
                        update.setObject(1, usuario);
                        update.setObject(2, batchId);
                        update.setObject(3, tenant);
                        if (update.executeUpdate() == 0) {
                          throw ApiException.notFound("Lote de ingesta no encontrado.");
                        }
                      }
                    }));
    Lote fresh = cargar(tenant, batchId);
    if (fresh == null) {
      throw ApiException.notFound("Lote de ingesta no encontrado.");
    }
    return fresh.snapshot();
  }

  private static UUID usuarioConfirmacion(String confirmadoPor, UUID actual) {
    if (confirmadoPor == null || confirmadoPor.isBlank()) {
      return actual;
    }
    try {
      return UUID.fromString(confirmadoPor.trim());
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("confirmadoPor no es un UUID.");
    }
  }

  private static int contarItems(Connection connection, UUID loteId, UUID tenant) throws java.sql.SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM app.ingesta_item WHERE lote_id = ? AND tenant_id = ?")) {
      query.setObject(1, loteId);
      query.setObject(2, tenant);
      try (ResultSet rs = query.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private static int contarRojos(Connection connection, UUID loteId, UUID tenant) throws java.sql.SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM app.ingesta_item
            WHERE lote_id = ? AND tenant_id = ?
              AND (detalle->>'semaforo' = 'ROJO' OR detalle->>'ocrEstado' = 'ERROR')
            """)) {
      query.setObject(1, loteId);
      query.setObject(2, tenant);
      try (ResultSet rs = query.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
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
    } catch (Exception e) {
      LOG.error("Fallo al insertar en app.jobs: ", e);
      throw ApiException.badRequest(
          "No se pudo encolar el procesamiento del documento. " + e.getMessage());
    }
    LOG.info("job PROCESAR_DOCUMENTO {}", payload);
  }

  private void encolarProcesarIa(Lote lote, Fila fila, AuthPrincipal principal) {
    ProcesarIaExpediente payload =
        new ProcesarIaExpediente(
            lote.id,
            fila.clientRowId,
            fila.expedienteId,
            principal.tenantId(),
            principal.userId(),
            fila.expedienteId.toString());
    try {
      jobs.encolar(principal.tenantId(), "PROCESAR_IA_EXPEDIENTE", objectMapper.writeValueAsString(payload));
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudo encolar el cotejo IA.");
    }
    LOG.info("job PROCESAR_IA_EXPEDIENTE {}", payload);
  }

  public void procesarDocumento(ProcesarDocumento payload) {
    Lote lote = cargar(payload.tenantId(), payload.loteId());
    Fila fila = lote == null ? null : filaONull(lote, payload.itemId());
    Archivo archivo = fila == null ? null : archivoDe(fila, payload.docId());
    UUID tenantId = payload.tenantId();
    UUID loteId = payload.loteId();
    try {
      if (payload.expedienteId() == null) {
        throw ApiException.badRequest("Sin expediente. No se llama a OCR.");
      }
      String sesion = payload.expedienteId().toString();
      StoredDoc guardado = carga.requireStoredDoc(sesion, payload.docId());
      if (guardado.bytes() == null || guardado.bytes().length == 0) {
        throw ApiException.badRequest("El archivo no está guardado. No se llama a OCR.");
      }
      LayoutExtractResult layout = ocr.evaluarYExtraer(guardado.bytes(), guardado.mimeType());
      String texto = layout.texto() == null ? "" : layout.texto();
      boolean legible =
          layout.calidad() != null && layout.calidad().legible() && StringUtils.hasText(texto);
      if (!legible) {
        abortarErrorTecnicoArchivo(lote, fila, archivo, tenantId, loteId, payload.itemId(), MSG_ILEGIBLE);
        return;
      }
      String tipo = archivo == null ? payload.tipoDocumento() : archivo.tipo;
      int confianza =
          layout.calidad() == null ? 0 : porcentaje(layout.calidad().promedioConfianza());
      carga.guardarResultadoOcr(
          sesion,
          new DocumentoOcrResultadoDTO(
              payload.docId(), guardado.nombreOriginal(), tipo, texto, null, "LEGIBLE", null, confianza));
      if (archivo != null) {
        synchronized (archivo) {
          archivo.texto = texto;
          archivo.legible = true;
          archivo.ocrListo = true;
          archivo.semaforo = semaforoDe(false, true, confianza);
          archivo.confianza = confianza;
        }
      }
      if (fila != null && lote != null) {
        if (!actualizarItemEnBd(lote.id, tenantId, fila, true)) {
          return;
        }
        actualizarContadoresLote(lote.id, tenantId, lote.fase);
      }
      lote = cargar(tenantId, loteId);
      fila = lote == null ? null : filaONull(lote, payload.itemId());
      if (filaExpedienteBloqueado(fila)) {
        bloquearExpedientePorArchivosConError(lote, fila, tenantId, loteId, payload.itemId());
        return;
      }
      if (fila != null && lote != null && esUltimoArchivo(fila, payload.expedienteId(), lote.id, tenantId)) {
        synchronized (fila) {
          fila.cerrarOcr(List.of());
        }
        persistirItemWorker(lote, fila);
      }
      if (lote != null) {
        emitirLoteActualizado(tenantId, loteId);
      }
    } catch (Exception ex) {
      String detalle = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
      LOG.error("job PROCESAR_DOCUMENTO ERROR payload={} error_log={}", payload, detalle);
      if (lote == null) {
        lote = cargar(tenantId, loteId);
      }
      if (fila == null && lote != null) {
        fila = filaONull(lote, payload.itemId());
      }
      archivo = fila == null ? archivo : archivoDe(fila, payload.docId());
      abortarErrorTecnicoArchivo(lote, fila, archivo, tenantId, loteId, payload.itemId(), detalle);
    }
  }

  private static final String MSG_ILEGIBLE = "El documento no contiene texto legible.";
  private static final String MSG_EXPEDIENTE_BLOQUEADO =
      "Expediente bloqueado. Corrija los archivos con error.";

  private static String mensajeErrorTecnicoArchivo(String detalle) {
    return "Error técnico en archivo: " + detalle;
  }

  private static boolean esFalloSubida(Archivo archivo) {
    return archivo != null
        && StringUtils.hasText(archivo.fileId)
        && archivo.fileId.startsWith("upload-error-");
  }

  /** Tras persistir el archivo actual, relee la fila desde BD (estado de otros workers). */
  private static boolean filaExpedienteBloqueado(Fila fila) {
    if (fila == null) {
      return false;
    }
    synchronized (fila) {
      if ("ERROR".equals(fila.ocrEstado) || "ERROR".equals(fila.iaEstado)) {
        return true;
      }
      for (Archivo item : fila.archivos) {
        if (esFalloSubida(item)) {
          return true;
        }
        if ("ROJO".equals(item.semaforo) || (item.ocrListo && !item.legible)) {
          return true;
        }
      }
    }
    return false;
  }

  private void bloquearExpedientePorArchivosConError(
      Lote lote, Fila fila, UUID tenantId, UUID loteId, String itemId) {
    if (fila != null) {
      synchronized (fila) {
        fila.ocrEstado = "ERROR";
        fila.semaforo = "ROJO";
        fila.iaEstado = "ERROR";
        fila.observaciones = MSG_EXPEDIENTE_BLOQUEADO;
        fila.iaResumen = MSG_EXPEDIENTE_BLOQUEADO;
      }
    }
    if (fila != null && lote != null) {
      persistirItemWorker(lote, fila);
      emitirItemError(loteId, itemId, MSG_EXPEDIENTE_BLOQUEADO);
    }
  }

  private void abortarErrorTecnicoArchivo(
      Lote lote,
      Fila fila,
      Archivo archivo,
      UUID tenantId,
      UUID loteId,
      String itemId,
      String detalle) {
    String observaciones = mensajeErrorTecnicoArchivo(detalle);
    if (fila != null) {
      synchronized (fila) {
        if (archivo != null) {
          archivo.fallo(detalle);
        }
        fila.ocrEstado = "ERROR";
        fila.semaforo = "ROJO";
        fila.iaEstado = "ERROR";
        fila.observaciones = observaciones;
        fila.iaResumen = observaciones;
      }
    }
    if (fila != null && lote != null) {
      persistirItemWorker(lote, fila);
      emitirItemError(loteId, itemId, observaciones);
    }
  }

  private void persistirItemWorker(Lote lote, Fila fila) {
    UUID tenant = lote.tenantId != null ? lote.tenantId : TenantContext.getTenantId();
    actualizarItemEnBd(lote.id, tenant, fila, false);
    actualizarContadoresLote(lote.id, tenant, lote.fase);
  }

  /**
   * Un solo worker por fila puede pasar de PENDIENTE a CONSOLIDANDO (disparo de IA).
   *
   * @return true si este worker tomó el lock
   */
  private boolean intentarTomarControlIa(UUID loteId, UUID tenant, String clientRowId) {
    int[] filas = new int[1];
    tx.executeWithoutResult(
        status ->
            entityManager
                .unwrap(Session.class)
                .doWork(
                    connection -> {
                      aplicarTenant(connection, tenant);
                      try (PreparedStatement update =
                          connection.prepareStatement(
                              """
                              UPDATE app.ingesta_item
                              SET detalle = jsonb_set(detalle, '{iaEstado}', '"CONSOLIDANDO"', true),
                                  estado = 'EN_PROCESO'
                              WHERE client_row_id = ? AND lote_id = ? AND tenant_id = ?
                                AND COALESCE(detalle->>'iaEstado', 'PENDIENTE') = 'PENDIENTE'
                              """)) {
                        update.setString(1, clientRowId);
                        update.setObject(2, loteId);
                        update.setObject(3, tenant);
                        filas[0] = update.executeUpdate();
                      }
                    }));
    return filas[0] == 1;
  }

  public void procesarIaExpediente(ProcesarIaExpediente payload) {
    try {
      Lote lote = cargar(payload.tenantId(), payload.loteId());
      Fila fila = lote == null ? null : filaONull(lote, payload.itemId());
      if (fila == null || lote == null || fila.expedienteId == null) {
        throw new IllegalStateException("Fila no encontrada: " + payload.itemId());
      }
      if (filaExpedienteBloqueado(fila)) {
        throw new IllegalStateException("Expediente bloqueado");
      }
      String sesion =
          StringUtils.hasText(payload.sessionId())
              ? payload.sessionId()
              : payload.expedienteId().toString();
      synchronized (fila) {
        fila.iaEstado = "EN_PROCESO";
      }
      persistirItemWorker(lote, fila);
      String unido = textoConsolidado(sesion);
      if (!StringUtils.hasText(unido)) {
        throw new IllegalStateException("Sin texto OCR consolidable.");
      }
      carga.guardarResultadoOcr(
          sesion,
          new DocumentoOcrResultadoDTO(
              "consolidado", "consolidado", "CONSOLIDADO", unido, null, "LEGIBLE", null, fila.confianza));
      ProcesarExpedienteCompletoResult resultado =
          ia.analizarExpedienteConPromptProducto(
              payload.expedienteId(), new AnalysisRequestDTO(Boolean.TRUE, sesion));
      String resumen = "OK";
      if (resultado.dictamen() != null && StringUtils.hasText(resultado.dictamen().estado())) {
        resumen = resultado.dictamen().estado();
      }
      synchronized (fila) {
        fila.iaEstado = "COMPLETADO";
        fila.iaResumen = resumen;
        fila.ocrEstado = "COMPLETADO";
        fila.duracionMs = Math.max(0, System.currentTimeMillis() - fila.inicioMs);
      }
      persistirItemWorker(lote, fila);
      emitirLoteActualizado(payload.tenantId(), payload.loteId(), true);
    } catch (RuntimeException ex) {
      String motivo =
          ex.getMessage() == null || ex.getMessage().isBlank()
              ? ex.getClass().getSimpleName()
              : ex.getMessage();
      LOG.error("job PROCESAR_IA_EXPEDIENTE ERROR payload={} error_log={}", payload, motivo);
      try {
        persistirErrorIa(payload, motivo);
      } catch (RuntimeException persistEx) {
        LOG.error("no se pudo persistir error IA item={}", payload.itemId(), persistEx);
      }
      emitirItemError(payload.loteId(), payload.itemId(), motivo);
    }
  }

  private void emitirLoteActualizado(UUID tenantId, UUID loteId) {
    emitirLoteActualizado(tenantId, loteId, false);
  }

  private void emitirLoteActualizado(UUID tenantId, UUID loteId, boolean iaCompletada) {
    Lote fresh = cargar(tenantId, loteId);
    if (fresh == null) {
      return;
    }
    emitir(loteId, "DOCUMENTO_LISTO", fresh.snapshot());
    if (iaCompletada) {
      emitir(loteId, "IA_COMPLETADA", fresh.snapshot());
    }
  }

  private boolean esUltimoArchivo(Fila fila, UUID expedienteId, UUID loteId, UUID tenantId) {
    if (expedienteId != null) {
      if (jobs.pendientesDelExpediente(expedienteId) > 0
          || jobs.enProcesoDelExpediente(expedienteId) > 1) {
        return false;
      }
    }
    if (fila == null || loteId == null || tenantId == null) {
      return true;
    }
    Integer pendientes =
        tx.execute(
            status ->
                entityManager
                    .unwrap(Session.class)
                    .doReturningWork(
                        connection -> {
                          aplicarTenant(connection, tenantId);
                          try (PreparedStatement query =
                              connection.prepareStatement(
                                  """
                                  SELECT COUNT(*)
                                  FROM app.ingesta_item i,
                                       jsonb_array_elements(i.detalle->'archivos') a
                                  WHERE i.client_row_id = ?
                                    AND i.lote_id = ?
                                    AND i.tenant_id = ?
                                    AND (a->>'ocrListo')::boolean = false
                                    AND (a->>'fallo')::boolean IS NOT TRUE
                                  """)) {
                            query.setString(1, fila.clientRowId);
                            query.setObject(2, loteId);
                            query.setObject(3, tenantId);
                            try (ResultSet rs = query.executeQuery()) {
                              return rs.next() ? rs.getInt(1) : 0;
                            }
                          }
                        }));
    return pendientes == null || pendientes == 0;
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

  private void emitirItemError(UUID loteId, String itemId, String mensaje) {
    for (SseEmitter emitter : streams.getOrDefault(loteId, new CopyOnWriteArrayList<>())) {
      try {
        emitter.send(SseEmitter.event().name("ITEM_ERROR").data(java.util.Map.of("itemId", itemId, "mensaje", mensaje)));
      } catch (IOException ex) {
        emitter.complete();
      }
    }
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

  private static String sqlLotes(boolean contar, boolean producto, boolean canton, boolean q, boolean status) {
    String cuerpo =
        """
        SELECT
          l.id,
          l.created_at,
          l.producto,
          l.canton,
          l.status_revision,
          l.estado,
          l.total_filas,
          COUNT(i.client_row_id) AS total_exp,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO' AND i.detalle->>'semaforo' = 'VERDE') AS verdes,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO' AND i.detalle->>'semaforo' = 'NARANJA') AS naranjas,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'ERROR' OR i.detalle->>'semaforo' = 'ROJO') AS rojos,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO') AS completados,
          COUNT(i.client_row_id) FILTER (WHERE i.estado IN ('PENDIENTE','EN_PROCESO')) AS en_proceso
        FROM app.ingesta_lote l
        LEFT JOIN app.ingesta_item i ON i.lote_id = l.id AND i.tenant_id = l.tenant_id
        WHERE l.tenant_id = :tenant
        """
            + (producto ? " AND l.producto = :producto" : "")
            + (canton ? " AND l.canton = :canton" : "")
            + (q ? " AND l.id::text ILIKE '%' || :q || '%'" : "")
            + """
         GROUP BY l.id, l.created_at, l.producto, l.canton, l.status_revision, l.estado, l.total_filas
        """
            + (status
                ? """
                 HAVING CASE
                   WHEN l.status_revision = 'CONFIRMADO' THEN 'CONFIRMADO'
                   WHEN COUNT(i.client_row_id) FILTER (WHERE i.estado IN ('PENDIENTE','EN_PROCESO')) > 0 THEN 'PROCESANDO'
                   WHEN COUNT(i.client_row_id) > 0 THEN 'REVISAR'
                   ELSE 'BORRADOR'
                 END = :status
                """
                : "");
    if (contar) {
      return "SELECT count(*) FROM (" + cuerpo + ") lotes";
    }
    return cuerpo + " ORDER BY l.created_at DESC LIMIT :limite OFFSET :offset";
  }

  private static String sqlLoteDetalle() {
    return """
        SELECT
          l.id,
          l.created_at,
          l.producto,
          l.canton,
          l.status_revision,
          l.estado,
          l.total_filas,
          COUNT(i.client_row_id) AS total_exp,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO' AND i.detalle->>'semaforo' = 'VERDE') AS verdes,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO' AND i.detalle->>'semaforo' = 'NARANJA') AS naranjas,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'ERROR' OR i.detalle->>'semaforo' = 'ROJO') AS rojos,
          COUNT(i.client_row_id) FILTER (WHERE i.estado = 'COMPLETADO') AS completados,
          COUNT(i.client_row_id) FILTER (WHERE i.estado IN ('PENDIENTE','EN_PROCESO')) AS en_proceso
        FROM app.ingesta_lote l
        LEFT JOIN app.ingesta_item i ON i.lote_id = l.id AND i.tenant_id = l.tenant_id
        WHERE l.tenant_id = :tenant AND l.id = :batchId
        GROUP BY l.id, l.created_at, l.producto, l.canton, l.status_revision, l.estado, l.total_filas
        """;
  }

  private jakarta.persistence.Query consultaLotes(
      boolean contar,
      UUID tenant,
      String producto,
      String canton,
      String q,
      String status,
      int limite,
      int offset) {
    var consulta =
        entityManager.createNativeQuery(
            sqlLotes(contar, producto != null, canton != null, q != null, status != null));
    consulta.setParameter("tenant", tenant);
    if (producto != null) {
      consulta.setParameter("producto", producto);
    }
    if (canton != null) {
      consulta.setParameter("canton", canton);
    }
    if (q != null) {
      consulta.setParameter("q", q);
    }
    if (status != null) {
      consulta.setParameter("status", status);
    }
    if (!contar) {
      consulta.setParameter("limite", limite);
      consulta.setParameter("offset", offset);
    }
    return consulta;
  }

  private static LoteListItemDto mapLoteListItem(Object[] row) {
    int totalFilas = numero(row[6]);
    int totalItems = numero(row[7]);
    int enProceso = numero(row[12]);
    return new LoteListItemDto(
        (UUID) row[0],
        aFecha(row[1]),
        row[2] == null ? null : String.valueOf(row[2]),
        row[3] == null ? null : String.valueOf(row[3]),
        totalFilas,
        numero(row[8]),
        numero(row[9]),
        numero(row[10]),
        statusCalculado(String.valueOf(row[4]), enProceso, totalItems, numero(row[10])),
        row[5] == null ? null : String.valueOf(row[5]),
        numero(row[11]),
        enProceso);
  }

  private static String statusCalculado(String persistido, int enProceso, int totalItems, int rojos) {
    if ("CONFIRMADO".equals(persistido)) {
      return "CONFIRMADO";
    }
    if (totalItems == 0) {
      return "BORRADOR";
    }
    if (rojos > 0 || enProceso > 0) {
      return "POR_REVISAR";
    }
    return "REVISAR";
  }

  private static String valorComun(List<String> valores) {
    Set<String> distintos = new LinkedHashSet<>();
    for (String valor : valores) {
      if (StringUtils.hasText(valor)) {
        distintos.add(valor.trim());
      }
    }
    if (distintos.isEmpty()) {
      return null;
    }
    if (distintos.size() == 1) {
      return distintos.iterator().next();
    }
    return "MIXTO";
  }

  private static String textoFiltro(String valor) {
    return StringUtils.hasText(valor) ? valor.trim() : null;
  }

  private static int numero(Object valor) {
    return valor == null ? 0 : ((Number) valor).intValue();
  }

  private static UUID uuidONull(Object valor) {
    if (valor instanceof UUID id) {
      return id;
    }
    return valor == null ? null : UUID.fromString(valor.toString());
  }

  private static String motivoSkipIa(Fila fila, UUID expedienteId) {
    if (expedienteId == null || fila == null) {
      return "ocrIncompleto";
    }
    for (Archivo archivo : fila.archivos) {
      if ("ROJO".equals(archivo.semaforo)) {
        return "semaforoRojo";
      }
    }
    if (!"LISTO".equals(fila.ocrEstado) && !"COMPLETADO".equals(fila.ocrEstado)) {
      return "ocrIncompleto";
    }
    return null;
  }

  private void persistirErrorIa(ProcesarIaExpediente payload, String motivo) {
    Lote lote = cargar(payload.tenantId(), payload.loteId());
    Fila fila = lote == null ? null : filaONull(lote, payload.itemId());
    if (lote == null || fila == null) {
      return;
    }
    synchronized (fila) {
      fila.iaEstado = "ERROR";
      fila.iaResumen = motivo;
    }
    persistirItemWorker(lote, fila);
  }

  private static OffsetDateTime aFecha(Object valor) {
    if (valor == null) {
      return null;
    }
    if (valor instanceof OffsetDateTime fecha) {
      return fecha;
    }
    if (valor instanceof Instant instante) {
      return instante.atOffset(ZoneOffset.UTC);
    }
    if (valor instanceof Timestamp marca) {
      return marca.toInstant().atOffset(ZoneOffset.UTC);
    }
    if (valor instanceof LocalDateTime local) {
      return local.atOffset(ZoneOffset.UTC);
    }
    return OffsetDateTime.parse(valor.toString());
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
    aplicarTenant(connection, tenant);
    try (PreparedStatement upsert =
        connection.prepareStatement(
            """
            INSERT INTO app.ingesta_lote
              (id, tenant_id, estado, total_filas, filas_completadas, producto, canton)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              estado = EXCLUDED.estado,
              total_filas = EXCLUDED.total_filas,
              filas_completadas = EXCLUDED.filas_completadas,
              producto = COALESCE(EXCLUDED.producto, app.ingesta_lote.producto),
              canton = COALESCE(EXCLUDED.canton, app.ingesta_lote.canton)
            """)) {
      upsert.setObject(1, lote.id);
      upsert.setObject(2, tenant);
      upsert.setString(3, lote.fase);
      upsert.setInt(4, lote.filas.size());
      upsert.setInt(5, hechas);
      upsert.setString(6, lote.producto);
      upsert.setString(7, lote.canton);
      upsert.executeUpdate();
    }
    int orden = 0;
    try (PreparedStatement upsertItem =
        connection.prepareStatement(
            """
            INSERT INTO app.ingesta_item
              (id, tenant_id, lote_id, client_row_id, orden, expediente_id, borrador_id,
               estado, porcentaje_progreso, error_log, detalle)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (lote_id, client_row_id) DO UPDATE SET
              orden = EXCLUDED.orden,
              expediente_id = EXCLUDED.expediente_id,
              borrador_id = EXCLUDED.borrador_id,
              estado = EXCLUDED.estado,
              porcentaje_progreso = EXCLUDED.porcentaje_progreso,
              error_log = EXCLUDED.error_log,
              detalle = EXCLUDED.detalle
            """)) {
      for (Fila fila : lote.filas) {
        String estado = estadoItem(fila);
        upsertItem.setObject(1, UUID.randomUUID());
        upsertItem.setObject(2, tenant);
        upsertItem.setObject(3, lote.id);
        upsertItem.setString(4, fila.clientRowId);
        upsertItem.setInt(5, orden++);
        upsertItem.setObject(6, fila.expedienteId);
        upsertItem.setObject(7, fila.borradorId);
        upsertItem.setString(8, estado);
        upsertItem.setInt(9, "COMPLETADO".equals(estado) ? 100 : 0);
        upsertItem.setString(10, "ERROR".equals(estado) ? fila.observaciones : null);
        upsertItem.setString(11, json(fila));
        upsertItem.executeUpdate();
      }
    }
  }

  /**
   * Actualiza una sola fila del lote.
   *
   * @param soloSiIaLibre si true, no sobrescribe filas en CONSOLIDANDO/COMPLETADO (OCR concurrente)
   * @return filas actualizadas (&gt; 0)
   */
  private boolean actualizarItemEnBd(UUID loteId, UUID tenant, Fila fila, boolean soloSiIaLibre) {
    int[] filas = new int[1];
    int[] existe = new int[1];
    tx.executeWithoutResult(
        status ->
            entityManager
                .unwrap(Session.class)
                .doWork(
                    connection -> {
                      aplicarTenant(connection, tenant);
                      String estado = estadoItem(fila);
                      String sql =
                          """
                          UPDATE app.ingesta_item
                          SET estado = ?, porcentaje_progreso = ?, error_log = ?, detalle = ?::jsonb
                          WHERE client_row_id = ? AND lote_id = ? AND tenant_id = ?
                          """
                              + (soloSiIaLibre
                                  ? " AND COALESCE(detalle->>'iaEstado', 'PENDIENTE') NOT IN ('CONSOLIDANDO', 'COMPLETADO')"
                                  : "");
                      try (PreparedStatement update = connection.prepareStatement(sql)) {
                        update.setString(1, estado);
                        update.setInt(2, "COMPLETADO".equals(estado) ? 100 : 0);
                        update.setString(3, "ERROR".equals(estado) ? fila.observaciones : null);
                        update.setString(4, json(fila));
                        update.setString(5, fila.clientRowId);
                        update.setObject(6, loteId);
                        update.setObject(7, tenant);
                        filas[0] = update.executeUpdate();
                        if (filas[0] == 0) {
                          try (PreparedStatement count =
                              connection.prepareStatement(
                                  """
                                  SELECT COUNT(*) FROM app.ingesta_item
                                  WHERE client_row_id = ? AND lote_id = ? AND tenant_id = ?
                                  """)) {
                            count.setString(1, fila.clientRowId);
                            count.setObject(2, loteId);
                            count.setObject(3, tenant);
                            try (ResultSet rs = count.executeQuery()) {
                              existe[0] = rs.next() ? rs.getInt(1) : 0;
                            }
                          }
                        }
                      }
                    }));
    if (filas[0] == 0) {
      if (existe[0] > 0 && !soloSiIaLibre) {
        LOG.error(
            "actualizarItemEnBd FAIL itemId={} loteId={} tenant={} — fila existe pero UPDATE afectó 0 filas. RLS o WHERE mal.",
            fila.clientRowId,
            loteId,
            tenant);
        throw new IllegalStateException("Persistencia item falló: " + fila.clientRowId);
      }
      if (existe[0] == 0) {
        LOG.error(
            "actualizarItemEnBd FAIL itemId={} loteId={} tenant={} — fila NO existe en BD",
            fila.clientRowId,
            loteId,
            tenant);
        throw new IllegalStateException("Item no existe en BD: " + fila.clientRowId);
      }
      if (soloSiIaLibre && existe[0] > 0) {
        LOG.warn("actualizarItemEnBd SKIP itemId={} por filtro soloSiIaLibre", fila.clientRowId);
        return false;
      }
    }
    return filas[0] > 0;
  }

  private void actualizarContadoresLote(UUID loteId, UUID tenant, String fase) {
    tx.executeWithoutResult(
        status ->
            entityManager
                .unwrap(Session.class)
                .doWork(
                    connection -> {
                      aplicarTenant(connection, tenant);
                      try (PreparedStatement update =
                          connection.prepareStatement(
                              """
                              UPDATE app.ingesta_lote SET
                                estado = ?,
                                total_filas = (
                                  SELECT count(*)::int FROM app.ingesta_item
                                  WHERE lote_id = ? AND tenant_id = ?),
                                filas_completadas = (
                                  SELECT count(*)::int FROM app.ingesta_item
                                  WHERE lote_id = ? AND tenant_id = ? AND estado = 'COMPLETADO')
                              WHERE id = ? AND tenant_id = ?
                              """)) {
                        update.setString(1, fase);
                        update.setObject(2, loteId);
                        update.setObject(3, tenant);
                        update.setObject(4, loteId);
                        update.setObject(5, tenant);
                        update.setObject(6, loteId);
                        update.setObject(7, tenant);
                        update.executeUpdate();
                      }
                    }));
  }

  private static void aplicarTenant(Connection connection, UUID tenant) throws java.sql.SQLException {
    try (PreparedStatement set =
        connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
      set.setString(1, tenant == null ? "" : tenant.toString());
      set.execute();
    }
  }

  private Lote cargar(UUID tenantId, UUID loteId) {
    List<?> fase =
        entityManager
            .createNativeQuery(
                "SELECT estado, producto, canton FROM app.ingesta_lote WHERE id = :id AND tenant_id = :tenant")
            .setParameter("id", loteId)
            .setParameter("tenant", tenantId)
            .getResultList();
    if (fase.isEmpty()) {
      return null;
    }
    Object[] cabecera = (Object[]) fase.get(0);
    Lote lote = new Lote(loteId, tenantId);
    lote.fase = String.valueOf(cabecera[0]);
    lote.producto = cabecera[1] == null ? null : String.valueOf(cabecera[1]);
    lote.canton = cabecera[2] == null ? null : String.valueOf(cabecera[2]);
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
    if ("PROCESANDO".equals(fila.ocrEstado)
        || "EN_PROCESO".equals(fila.iaEstado)
        || "CONSOLIDANDO".equals(fila.iaEstado)) {
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

  /** ROJO > NARANJA > VERDE > GRIS */
  private static String peor(String actual, String siguiente) {
    return rangoSemaforo(siguiente) > rangoSemaforo(actual)
        ? normSemaforo(siguiente)
        : normSemaforo(actual);
  }

  private static int rangoSemaforo(String color) {
    if ("ROJO".equals(color)) {
      return 3;
    }
    if ("NARANJA".equals(color)) {
      return 2;
    }
    if ("VERDE".equals(color)) {
      return 1;
    }
    return 0;
  }

  private static String normSemaforo(String color) {
    if ("ROJO".equals(color) || "NARANJA".equals(color) || "VERDE".equals(color)) {
      return color;
    }
    return "GRIS";
  }

  private static String shortUuid(UUID id) {
    String raw = id.toString().replace("-", "");
    return raw.substring(0, Math.min(8, raw.length())).toUpperCase();
  }

  private static OffsetDateTime ultimaActualizacion(List<Fila> filas) {
    long maxMs = 0;
    for (Fila fila : filas) {
      long fin = fila.inicioMs + Math.max(0L, fila.duracionMs);
      if (fin > maxMs) {
        maxMs = fin;
      }
    }
    if (maxMs <= 0) {
      return null;
    }
    return OffsetDateTime.ofInstant(Instant.ofEpochMilli(maxMs), ZoneOffset.UTC);
  }

  private static final class Lote {
    private final UUID id;
    private final UUID tenantId;
    private final List<Fila> filas = new ArrayList<>();
    private String fase = "CREADO";
    private String producto;
    private String canton;

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
      for (Fila fila : filas) {
        if (filaListaParaCotejo(fila)) {
          return true;
        }
      }
      return false;
    }

    private static boolean filaListaParaCotejo(Fila fila) {
      synchronized (fila) {
        if ("ERROR".equals(fila.ocrEstado) || "ERROR".equals(fila.iaEstado)) {
          return false;
        }
        if (fila.expedienteId == null || fila.archivos.isEmpty() || "PROCESANDO".equals(fila.ocrEstado)) {
          return false;
        }
        if ("VERDE".equals(fila.semaforo) || "NARANJA".equals(fila.semaforo)) {
          return true;
        }
        return "LISTO".equals(fila.ocrEstado) || "COMPLETADO".equals(fila.ocrEstado);
      }
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
    private String cliente;
    private String clienteIdentificacion;
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
      int procesables = 0;
      for (Archivo archivo : archivos) {
        if (esFalloSubida(archivo)) {
          continue;
        }
        procesables++;
        color = peor(color, archivo.semaforo);
        suma += archivo.confianza;
        if (StringUtils.hasText(archivo.mensaje)) {
          notas.add(archivo.nombre + ": " + archivo.mensaje);
        }
      }
      if (procesables == 0) {
        ocrEstado = "ERROR";
        semaforo = "ROJO";
        return;
      }
      if (faltantes != null && !faltantes.isEmpty()) {
        if (!"ROJO".equals(color)) {
          color = "NARANJA";
        }
        notas.add("Falta documento requerido: " + String.join(", ", faltantes));
      }
      ocrEstado = "LISTO";
      semaforo = color;
      confianza = suma / procesables;
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
      ocrListo = false;
    }

    private ArchivoStatus status() {
      return new ArchivoStatus(
          fileId, nombre, tipo, semaforo, confianza, legible, mensaje, recortar(texto, TEXTO_PREVIEW));
    }
  }
}
