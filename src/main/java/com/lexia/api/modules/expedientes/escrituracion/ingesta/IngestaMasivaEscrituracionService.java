package com.lexia.api.modules.expedientes.escrituracion.ingesta;

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
import com.lexia.api.modules.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

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
  private final Map<UUID, Lote> lotes = new ConcurrentHashMap<>();
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          3,
          runnable -> {
            Thread thread = new Thread(runnable, "ingesta-masiva-escrituracion");
            thread.setDaemon(true);
            return thread;
          });

  public IngestaMasivaEscrituracionService(
      CargaDocumentoService carga,
      CaseDocumentoStore casos,
      ExpedienteBorradorStore borradores,
      BorradorPromocionService promocion,
      OcrAzureBatchService ocr,
      IaAnalysisService ia,
      ProductoBiessService productos) {
    this.carga = carga;
    this.casos = casos;
    this.borradores = borradores;
    this.promocion = promocion;
    this.ocr = ocr;
    this.ia = ia;
    this.productos = productos;
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
    lotes.put(lote.id, lote);
    LOG.info("ingesta-masiva lote={} filas={} tenant={}", lote.id, lote.filas.size(), principal.tenantId());
    return lote.snapshot();
  }

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
      encolarOcr(lote, fila, principal);
    }
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
      }
    }
    synchronized (lote) {
      lote.fase = soloIa ? "IA" : "OCR";
    }
    if (soloIa) {
      encolarIa(lote, fila, principal);
    } else {
      encolarOcr(lote, fila, principal);
    }
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
    encolarOcr(lote, fila, principal);
    return lote.snapshot();
  }

  public LoteStatus status(UUID batchId) {
    return require(batchId).snapshot();
  }

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
    workers.submit(
        () -> {
          AuthContext.set(principal);
          TenantContext.setTenantId(principal.tenantId());
          try {
            List<String> faltantes = documentosFaltantes(fila);
            synchronized (fila) {
              String sesion = fila.expedienteId == null ? null : fila.expedienteId.toString();
              for (Archivo archivo : fila.archivos) {
                if (archivo.ocrListo) {
                  continue;
                }
                if (sesion == null) {
                  archivo.fallo("Sin expediente. No se llama a OCR.");
                  continue;
                }
                try {
                  StoredDoc guardado = carga.requireStoredDoc(sesion, archivo.fileId);
                  if (guardado.bytes() == null || guardado.bytes().length == 0) {
                    archivo.fallo("El archivo no está guardado. No se llama a OCR.");
                    continue;
                  }
                  AnalyzeSingleResponse respuesta =
                      ocr.analyzeSingle(new AnalyzeSingleRequest(sesion, archivo.fileId, archivo.tipo));
                  archivo.aplicar(respuesta);
                } catch (RuntimeException ex) {
                  archivo.fallo(mensaje(ex));
                }
              }
              fila.cerrarOcr(faltantes);
            }
            synchronized (lote) {
              lote.refrescarFaseOcr();
            }
          } finally {
            AuthContext.clear();
            TenantContext.clear();
          }
        });
  }

  private void encolarIa(Lote lote, Fila fila, AuthPrincipal principal) {
    workers.submit(
        () -> {
          AuthContext.set(principal);
          TenantContext.setTenantId(principal.tenantId());
          try {
            String texto = fila.textoConsolidado();
            if (!StringUtils.hasText(texto)) {
              synchronized (fila) {
                fila.iaEstado = "ERROR";
                fila.iaResumen = "Sin texto OCR para analizar.";
              }
              return;
            }
            if (fila.expedienteId == null || fila.borradorId == null) {
              synchronized (fila) {
                fila.iaEstado = "ERROR";
                fila.iaResumen = "La fila no tiene expediente asociado.";
              }
              return;
            }
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
            synchronized (fila) {
              fila.iaEstado = "OK";
              fila.iaResumen = resumen;
            }
          } catch (RuntimeException ex) {
            synchronized (fila) {
              fila.iaEstado = "ERROR";
              fila.iaResumen = mensaje(ex);
            }
          } finally {
            AuthContext.clear();
            TenantContext.clear();
            synchronized (lote) {
              lote.refrescarFaseIa();
            }
          }
        });
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
    Lote lote = lotes.get(batchId);
    if (lote == null || !lote.tenantId.equals(principal.tenantId())) {
      throw ApiException.notFound("Lote de ingesta no encontrado.");
    }
    return lote;
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
    private final String fileId;
    private final String nombre;
    private final String tipo;
    private String semaforo = "GRIS";
    private int confianza;
    private boolean legible;
    private String mensaje;
    private String texto;
    private boolean ocrListo;

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

    private ArchivoStatus status() {
      return new ArchivoStatus(
          fileId, nombre, tipo, semaforo, confianza, legible, mensaje, recortar(texto, TEXTO_PREVIEW));
    }
  }
}
