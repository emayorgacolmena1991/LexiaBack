package com.lexia.api.modules.expedientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.actos.ActosDtos.DocumentoRequeridoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.BorradorResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.IniciarProcesamientoResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoActualizadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoPermitidoDTO;
import java.io.IOException;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Carga y tipificación (paso 2). Guarda archivos en memoria de sesión de borrador.
 * {@code iniciar-procesamiento} bloquea el borrador y dispara la prevalidación en segundo plano.
 */
@Service
public class CargaDocumentoService {

  private static final long MAX_BYTES = 25L * 1024 * 1024;
  private static final Set<String> ALLOWED_EXT =
      Set.of("pdf", "jpg", "jpeg", "png", "tiff", "tif");

  private final ActoNotarialService actoNotarialService;
  private final PrevalidacionService prevalidacionService;
  private final Map<String, DraftExpediente> drafts = new ConcurrentHashMap<>();
  private final AtomicInteger seq = new AtomicInteger(480);

  public CargaDocumentoService(
      ActoNotarialService actoNotarialService, PrevalidacionService prevalidacionService) {
    this.actoNotarialService = actoNotarialService;
    this.prevalidacionService = prevalidacionService;
  }

  public BorradorResponse crearBorrador(String idActo) {
    // Valida acto y requisitos contra catálogo real.
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
  }

  public IniciarProcesamientoResponse iniciarProcesamiento(String idExpediente) {
    DraftExpediente draft = requireDraft(idExpediente);
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
    draft.lock();
    prevalidacionService.iniciar(idExpediente, new ArrayList<>(draft.documentos().values()));
    return new IniciarProcesamientoResponse(
        idExpediente,
        "EN_PROCESAMIENTO_IA",
        3,
        "Documentos enviados a extractor OCR y motor Gemini");
  }

  /** Bytes guardados para pantallas posteriores (OCR). */
  public List<StoredDoc> documentosParaProcesar(String idExpediente) {
    return new ArrayList<>(requireDraft(idExpediente).documentos().values());
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
    private final Map<String, StoredDoc> documentos = new ConcurrentHashMap<>();
    private volatile boolean locked;

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

    boolean locked() {
      return locked;
    }

    void lock() {
      this.locked = true;
    }
  }

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
