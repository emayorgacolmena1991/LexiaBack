package com.lexia.api.modules.expedientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.actos.ActosDtos.DocumentoRequeridoDTO;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.BorradorResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.IniciarProcesamientoResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.PrevalidacionDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.PrevalidacionDocumentoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoActualizadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoPermitidoDTO;
import java.io.IOException;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Carga y tipificación (paso 2). Al iniciar procesamiento dispara Azure→Gemini vía {@link
 * ProcesamientoDocumentalService}; el FE hace poll de {@link #obtenerPrevalidacion}.
 */
@Service
public class CargaDocumentoService {

  private static final long MAX_BYTES = 25L * 1024 * 1024;
  private static final Set<String> ALLOWED_EXT =
      Set.of("pdf", "jpg", "jpeg", "png", "tiff", "tif");

  private final ActoNotarialService actoNotarialService;
  private final ProcesamientoDocumentalService procesamientoDocumentalService;
  private final DocumentoTextoOcrRepository ocrRepository;
  private final Map<String, DraftExpediente> drafts = new ConcurrentHashMap<>();
  private final AtomicInteger seq = new AtomicInteger(480);

  public CargaDocumentoService(
      ActoNotarialService actoNotarialService,
      @Lazy ProcesamientoDocumentalService procesamientoDocumentalService,
      DocumentoTextoOcrRepository ocrRepository) {
    this.actoNotarialService = actoNotarialService;
    this.procesamientoDocumentalService = procesamientoDocumentalService;
    this.ocrRepository = ocrRepository;
  }

  public BorradorResponse crearBorrador(String idActo) {
    actoNotarialService.obtenerRequisitosPorActo(idActo);
    String id =
        "EXP-"
            + Year.now().getValue()
            + "-"
            + String.format(Locale.ROOT, "%05d", seq.incrementAndGet());
    drafts.put(id, new DraftExpediente(id, idActo.trim()));
    return new BorradorResponse(id, idActo.trim(), "BORRADOR");
  }

  public List<TipoPermitidoDTO> tiposPermitidos(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    ActoNotarialRespuestaDTO requisitos =
        actoNotarialService.obtenerRequisitosPorActo(draft.idActo());
    List<DocumentoRequeridoDTO> docs =
        requisitos.documentos() == null ? List.of() : requisitos.documentos();
    return docs.stream()
        .map(d -> new TipoPermitidoDTO(d.codigoDocumento(), d.nombre()))
        .toList();
  }

  public List<DocumentoCargadoDTO> listarDocumentos(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    return draft.documentos().values().stream().map(StoredDoc::toDto).toList();
  }

  public DocumentoCargadoDTO subir(String idExpediente, MultipartFile file) {
    DraftExpediente draft = requireDraft(idExpediente);
    if (draft.locked()) {
      throw ApiException.conflict("Los documentos ya están en procesamiento y no se pueden modificar.");
    }
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Archivo vacío.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ApiException.badRequest("El archivo supera el máximo de 25 MB.");
    }
    String original =
        StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "archivo";
    String ext = extensionOf(original);
    if (!ALLOWED_EXT.contains(ext)) {
      throw ApiException.badRequest("Formato no permitido. Usa PDF, JPG, PNG o TIFF.");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo.");
    }

    String idDoc = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    StoredDoc stored =
        new StoredDoc(
            idDoc,
            original,
            file.getSize(),
            formatSize(file.getSize()),
            file.getContentType(),
            bytes,
            null);
    draft.documentos().put(idDoc, stored);
    return stored.toDto();
  }

  /** Lookup bytes del borrador (sessionId = idExpediente). */
  public StoredDoc requireStoredDoc(String sessionId, String fileId) {
    DraftExpediente draft = requireDraft(sessionId);
    StoredDoc doc = draft.documentos().get(fileId);
    if (doc == null) {
      throw ApiException.notFound("Documento no encontrado: " + fileId);
    }
    return doc;
  }

  /**
   * Re-subida OCR (LoadDocuments): reemplaza bytes sin re-procesar el resto.
   * Permitido aunque el borrador esté locked (pantalla OCR review).
   */
  public StoredDoc reemplazarArchivoOcr(
      String sessionId, String fileId, MultipartFile file, String tipoDocumento) {
    DraftExpediente draft = requireDraft(sessionId);
    StoredDoc existing = draft.documentos().get(fileId);
    if (existing == null) {
      throw ApiException.notFound("Documento no encontrado: " + fileId);
    }
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Archivo vacío.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ApiException.badRequest("El archivo supera el máximo de 25 MB.");
    }
    String original =
        StringUtils.hasText(file.getOriginalFilename())
            ? file.getOriginalFilename()
            : existing.nombreOriginal();
    String ext = extensionOf(original);
    if (!ALLOWED_EXT.contains(ext)) {
      throw ApiException.badRequest("Formato no permitido. Usa PDF, JPG, PNG o TIFF.");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw ApiException.badRequest("No se pudo leer el archivo.");
    }
    String tipo =
        StringUtils.hasText(tipoDocumento)
            ? tipoDocumento.trim()
            : existing.codigoTipoDocumento();
    StoredDoc replaced =
        new StoredDoc(
            fileId,
            original,
            file.getSize(),
            formatSize(file.getSize()),
            file.getContentType(),
            bytes,
            tipo);
    draft.documentos().put(fileId, replaced);
    draft.ocrResultados().remove(fileId);
    return replaced;
  }

  /** Actualiza tipo sin validar catálogo (payload batch OCR). */
  public void actualizarTipoDocumentoLibre(String sessionId, String fileId, String tipo) {
    DraftExpediente draft = requireDraft(sessionId);
    StoredDoc doc = draft.documentos().get(fileId);
    if (doc == null) {
      throw ApiException.notFound("Documento no encontrado: " + fileId);
    }
    draft.documentos().put(fileId, doc.withTipo(tipo.trim()));
  }

  public TipoActualizadoDTO actualizarTipo(
      String idExpediente, String idDocumento, ActualizarTipoRequest body) {
    DraftExpediente draft = requireDraft(idExpediente);
    if (draft.locked()) {
      throw ApiException.conflict("Los documentos ya están en procesamiento y no se pueden modificar.");
    }
    StoredDoc doc = draft.documentos().get(idDocumento);
    if (doc == null) {
      throw ApiException.notFound("Documento no encontrado: " + idDocumento);
    }
    String codigo = body.codigoTipoDocumento().trim();
    Set<String> permitidos =
        tiposPermitidos(idExpediente).stream()
            .map(TipoPermitidoDTO::codigo)
            .collect(java.util.stream.Collectors.toSet());
    if (!permitidos.isEmpty() && !permitidos.contains(codigo)) {
      throw ApiException.badRequest("Tipo documental no permitido para este expediente.");
    }
    StoredDoc updated = doc.withTipo(codigo);
    draft.documentos().put(idDocumento, updated);
    return new TipoActualizadoDTO(idDocumento, codigo, "CLASIFICADO");
  }

  public void eliminar(String idExpediente, String idDocumento) {
    DraftExpediente draft = requireDraft(idExpediente);
    if (draft.locked()) {
      throw ApiException.conflict("Los documentos ya están en procesamiento y no se pueden modificar.");
    }
    if (draft.documentos().remove(idDocumento) == null) {
      throw ApiException.notFound("Documento no encontrado: " + idDocumento);
    }
    draft.ocrResultados().remove(idDocumento);
  }

  /** POST iniciar-procesamiento / procesar-ia: encola Azure→Gemini una sola vez. */
  public IniciarProcesamientoResponse iniciarProcesamiento(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    if (draft.locked()) {
      return procesamientoEncolado(idExpediente);
    }
    if (draft.documentos().isEmpty()) {
      throw ApiException.badRequest("Debes cargar al menos un documento.");
    }
    List<StoredDoc> sinTipo =
        draft.documentos().values().stream()
            .filter(d -> !StringUtils.hasText(d.codigoTipoDocumento()))
            .toList();
    if (!sinTipo.isEmpty()) {
      throw ApiException.badRequest("Clasifica todos los archivos antes de continuar.");
    }
    if (!draft.startProcessingIfIdle()) {
      return procesamientoEncolado(idExpediente);
    }

    List<PrevalidacionDocumentoDTO> iniciales =
        draft.documentos().values().stream()
            .map(
                d ->
                    new PrevalidacionDocumentoDTO(
                        d.idDocumento(),
                        d.nombreOriginal(),
                        d.codigoTipoDocumento(),
                        "EN_PROCESO",
                        null))
            .toList();
    draft.initPrevalidacion(iniciales);
    draft.clearOcrResultados();

    AuthPrincipal auth = AuthContext.get();
    UUID tenantId = auth != null ? auth.tenantId() : null;
    procesamientoDocumentalService.procesarExpedienteCompletoAsync(idExpediente, tenantId, auth);

    return procesamientoEncolado(idExpediente);
  }

  private static IniciarProcesamientoResponse procesamientoEncolado(String idExpediente) {
    return new IniciarProcesamientoResponse(
        idExpediente,
        "EN_PROCESAMIENTO_IA",
        3,
        "Documentos enviados a extractor OCR Azure y motor Gemini");
  }

  public PrevalidacionDTO obtenerPrevalidacion(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    PrevalidacionState state = draft.prevalidacion();
    if (state == null) {
      List<PrevalidacionDocumentoDTO> docs =
          draft.documentos().values().stream()
              .map(
                  d ->
                      new PrevalidacionDocumentoDTO(
                          d.idDocumento(),
                          d.nombreOriginal(),
                          d.codigoTipoDocumento(),
                          "EN_PROCESO",
                          null))
              .toList();
      return new PrevalidacionDTO(idExpediente, "EN_PROCESO", 0, 0, docs.size(), docs);
    }
    return new PrevalidacionDTO(
        idExpediente,
        state.estado(),
        state.confianza(),
        state.legibles(),
        state.total(),
        List.copyOf(state.documentos().values()));
  }

  /**
   * OCR/Gemini en memoria del borrador (no DB). Solo docs del draft actual. Persistencia a DB al
   * crear expediente vía {@link #persistirOcrAlCrearExpediente}.
   */
  public List<DocumentoOcrResultadoDTO> listarOcrResultados(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    return draft.documentos().keySet().stream()
        .map(idDoc -> draft.ocrResultados().get(idDoc))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** Guarda resultado OCR+Gemini solo en RAM del borrador. */
  public void guardarOcrEnMemoria(String idExpediente, DocumentoOcrResultadoDTO resultado) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null || resultado == null) {
      return;
    }
    draft.ocrResultados().put(resultado.idDocumento(), resultado);
  }

  /**
   * Vuelca OCR del borrador a {@code app.documento_texto_ocr}. Llamar al crear expediente definitivo.
   */
  public void persistirOcrAlCrearExpediente(String idExpediente, UUID tenantId) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null) {
      return;
    }
    for (DocumentoOcrResultadoDTO r : draft.ocrResultados().values()) {
      try {
        DocumentoTextoOcr row =
            ocrRepository
                .findByIdExpedienteAndIdDocumento(idExpediente, r.idDocumento())
                .orElseGet(DocumentoTextoOcr::new);
        row.setTenantId(tenantId);
        row.setIdExpediente(idExpediente);
        row.setIdDocumento(r.idDocumento());
        row.setTipoDocumento(r.tipoDocumento());
        row.setTextoOcr(r.textoOcr());
        row.setAnalisisJson(r.analisisJson());
        row.setEstadoDoc(r.estado());
        row.setMotivo(r.motivo());
        row.setConfianza(r.confianza());
        ocrRepository.save(row);
      } catch (Exception e) {
        // no bloquear alta si falla el volcado OCR
      }
    }
  }

  void actualizarDocPrevalidacion(
      String idExpediente, String idDocumento, String estado, String motivo, int confianza) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null) {
      return;
    }
    draft.patchDocPrevalidacion(idDocumento, estado, motivo, confianza);
  }

  void completarPrevalidacion(
      String idExpediente,
      String estado,
      int confianza,
      int legibles,
      int total,
      List<PrevalidacionDocumentoDTO> documentos) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null) {
      return;
    }
    draft.completePrevalidacion(estado, confianza, legibles, total, documentos);
  }

  void marcarPrevalidacionError(String idExpediente, String motivo) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null) {
      return;
    }
    draft.failPrevalidacion(motivo);
  }

  /** Bytes guardados para OCR (orden de carga). */
  public List<StoredDoc> documentosParaProcesar(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
    synchronized (draft.documentos()) {
      return new ArrayList<>(draft.documentos().values());
    }
  }

  private DraftExpediente requireDraft(String idExpediente) {
    DraftExpediente draft = drafts.get(idExpediente);
    if (draft == null) {
      throw ApiException.notFound("Expediente borrador no encontrado: " + idExpediente);
    }
    return draft;
  }

  private static String extensionOf(String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "";
    }
    return name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double kb = bytes / 1024.0;
    if (kb < 1024) {
      return (kb < 10 ? String.format(Locale.ROOT, "%.1f", kb) : String.valueOf(Math.round(kb)))
          + " KB";
    }
    double mb = kb / 1024.0;
    return (mb < 10 ? String.format(Locale.ROOT, "%.1f", mb) : String.valueOf(Math.round(mb)))
        + " MB";
  }

  static final class DraftExpediente {
    private final String id;
    private final String idActo;
    private final Map<String, StoredDoc> documentos =
        Collections.synchronizedMap(new LinkedHashMap<>());
    /** OCR+LLM solo en RAM hasta crear expediente. */
    private final Map<String, DocumentoOcrResultadoDTO> ocrResultados = new ConcurrentHashMap<>();
    private volatile boolean locked;
    private volatile PrevalidacionState prevalidacion;

    DraftExpediente(String id, String idActo) {
      this.id = id;
      this.idActo = idActo;
    }

    String id() {
      return id;
    }

    String idActo() {
      return idActo;
    }

    Map<String, StoredDoc> documentos() {
      return documentos;
    }

    Map<String, DocumentoOcrResultadoDTO> ocrResultados() {
      return ocrResultados;
    }

    void clearOcrResultados() {
      ocrResultados.clear();
    }

    synchronized boolean locked() {
      return locked;
    }

    /** Primer inicio gana. Un segundo iniciar-procesamiento no relanza el job. */
    synchronized boolean startProcessingIfIdle() {
      if (locked) {
        return false;
      }
      locked = true;
      return true;
    }

    PrevalidacionState prevalidacion() {
      return prevalidacion;
    }

    void initPrevalidacion(List<PrevalidacionDocumentoDTO> docs) {
      Map<String, PrevalidacionDocumentoDTO> map = new ConcurrentHashMap<>();
      for (PrevalidacionDocumentoDTO d : docs) {
        map.put(d.idDocumento(), d);
      }
      this.prevalidacion = new PrevalidacionState("EN_PROCESO", 0, 0, docs.size(), map);
    }

    synchronized void patchDocPrevalidacion(
        String idDocumento, String estado, String motivo, int confianza) {
      PrevalidacionState current = this.prevalidacion;
      if (current == null) {
        return;
      }
      PrevalidacionDocumentoDTO prev = current.documentos().get(idDocumento);
      if (prev == null) {
        return;
      }
      current
          .documentos()
          .put(
              idDocumento,
              new PrevalidacionDocumentoDTO(
                  prev.idDocumento(),
                  prev.nombreOriginal(),
                  prev.tipoDocumento(),
                  estado,
                  motivo));
      int legibles =
          (int)
              current.documentos().values().stream()
                  .filter(d -> "LEGIBLE".equals(d.estado()))
                  .count();
      this.prevalidacion =
          new PrevalidacionState(
              "EN_PROCESO",
              confianza,
              legibles,
              current.total(),
              current.documentos());
    }

    synchronized void completePrevalidacion(
        String estado,
        int confianza,
        int legibles,
        int total,
        List<PrevalidacionDocumentoDTO> documentos) {
      Map<String, PrevalidacionDocumentoDTO> map = new ConcurrentHashMap<>();
      for (PrevalidacionDocumentoDTO d : documentos) {
        map.put(d.idDocumento(), d);
      }
      this.prevalidacion = new PrevalidacionState(estado, confianza, legibles, total, map);
    }

    synchronized void failPrevalidacion(String motivo) {
      PrevalidacionState current = this.prevalidacion;
      Map<String, PrevalidacionDocumentoDTO> map = new ConcurrentHashMap<>();
      if (current != null) {
        for (PrevalidacionDocumentoDTO d : current.documentos().values()) {
          map.put(
              d.idDocumento(),
              new PrevalidacionDocumentoDTO(
                  d.idDocumento(),
                  d.nombreOriginal(),
                  d.tipoDocumento(),
                  "ERROR",
                  motivo));
        }
        this.prevalidacion = new PrevalidacionState("ERROR", 0, 0, current.total(), map);
      } else {
        this.prevalidacion = new PrevalidacionState("ERROR", 0, 0, 0, map);
      }
    }
  }

  record PrevalidacionState(
      String estado,
      int confianza,
      int legibles,
      int total,
      Map<String, PrevalidacionDocumentoDTO> documentos) {}

  public record StoredDoc(
      String idDocumento,
      String nombreOriginal,
      long sizeBytes,
      String tamano,
      String mimeType,
      byte[] bytes,
      String codigoTipoDocumento) {

    StoredDoc withTipo(String codigo) {
      return new StoredDoc(
          idDocumento, nombreOriginal, sizeBytes, tamano, mimeType, bytes, codigo);
    }

    DocumentoCargadoDTO toDto() {
      return new DocumentoCargadoDTO(idDocumento, nombreOriginal, tamano, codigoTipoDocumento);
    }
  }
}
