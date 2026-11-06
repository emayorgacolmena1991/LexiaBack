package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.BorradorResponse;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.CrearBorradorRequest;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.IniciarProcesamientoResponse;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.PrevalidacionDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoActualizadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResultadoCotejoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService.StoredDoc;
import com.lexia.api.modules.identity.AuthorizationService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.lexia.api.modules.expedientes.reglas.ValidacionIaService;

/**
 * Paso 2–3: carga/tipificación + disparo IA (Azure→Gemini/Claude) + prevalidación + cotejo.
 */
@RestController
@RequestMapping("/api/v1/expedientes")
public class CargaDocumentoController {

  /** El MIME lo declara el cliente: fuera de estos tipos no se sirve tal cual (evita HTML activo). */
  private static final Set<String> TIPOS_VISIBLES =
      Set.of("application/pdf", "image/jpeg", "image/png", "image/tiff");

  private final CargaDocumentoService cargaDocumentoService;
  private final ValidacionIaService validacionIaService;
  private final ObjectProvider<AuthorizationService> authorization;

  public CargaDocumentoController(
      CargaDocumentoService cargaDocumentoService,
      ValidacionIaService validacionIaService,
      ObjectProvider<AuthorizationService> authorization) {
    this.cargaDocumentoService = cargaDocumentoService;
    this.validacionIaService = validacionIaService;
    this.authorization = authorization;
  }

  @PostMapping("/borrador")
  @ResponseStatus(HttpStatus.OK)
  public BorradorResponse crearBorrador(@Valid @RequestBody CrearBorradorRequest request) {
    return cargaDocumentoService.crearBorrador(request);
  }

  @GetMapping("/{idExpediente}/tipos-permitidos")
  public List<TipoPermitidoDTO> tiposPermitidos(@PathVariable String idExpediente) {
    return cargaDocumentoService.tiposPermitidos(idExpediente);
  }

  @GetMapping("/{idExpediente}/documentos")
  public List<DocumentoCargadoDTO> listarDocumentos(@PathVariable String idExpediente) {
    return cargaDocumentoService.listarDocumentos(idExpediente);
  }

  @PostMapping(
      value = "/{idExpediente}/documentos/upload",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentoCargadoDTO> subir(
      @PathVariable String idExpediente, @RequestParam("file") MultipartFile file) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(cargaDocumentoService.subir(idExpediente, file));
  }

  /** Última versión del archivo; el tenant y el expediente se validan al resolver el documento. */
  @GetMapping("/{idExpediente}/documentos/{idDocumento}/contenido")
  public ResponseEntity<byte[]> contenido(
      @PathVariable String idExpediente, @PathVariable String idDocumento) {
    authorization.ifAvailable(service -> service.requirePermission("expedientes:caso:leer"));
    StoredDoc doc = cargaDocumentoService.requireStoredDoc(idExpediente, idDocumento);
    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename(doc.nombreOriginal(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentType(tipoSeguro(doc.mimeType()))
        .body(doc.bytes());
  }

  static MediaType tipoSeguro(String mime) {
    String normalizado = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
    return TIPOS_VISIBLES.contains(normalizado)
        ? MediaType.parseMediaType(normalizado)
        : MediaType.APPLICATION_OCTET_STREAM;
  }

  @PatchMapping("/{idExpediente}/documentos/{idDocumento}/tipo")
  public TipoActualizadoDTO actualizarTipo(
      @PathVariable String idExpediente,
      @PathVariable String idDocumento,
      @Valid @RequestBody ActualizarTipoRequest body) {
    return cargaDocumentoService.actualizarTipo(idExpediente, idDocumento, body);
  }

  @DeleteMapping("/{idExpediente}/documentos/{idDocumento}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminar(@PathVariable String idExpediente, @PathVariable String idDocumento) {
    cargaDocumentoService.eliminar(idExpediente, idDocumento);
  }

  @PostMapping("/{idExpediente}/iniciar-procesamiento")
  public IniciarProcesamientoResponse iniciarProcesamiento(@PathVariable String idExpediente) {
    return cargaDocumentoService.iniciarProcesamiento(idExpediente);
  }

  /** Alias del algoritmo (mismo efecto que iniciar-procesamiento). */
  @PostMapping("/{idExpediente}/procesar-ia")
  public IniciarProcesamientoResponse procesarIa(@PathVariable String idExpediente) {
    return cargaDocumentoService.iniciarProcesamiento(idExpediente);
  }

  @GetMapping("/{idExpediente}/prevalidacion")
  public PrevalidacionDTO prevalidacion(@PathVariable String idExpediente) {
    return cargaDocumentoService.obtenerPrevalidacion(idExpediente);
  }

  /** Cotejo notarial Claude sobre OCR consolidado (Cédula↔Papeleta, Avalúo↔Historia). */
  @PostMapping("/{idExpediente}/validar-ia")
  public ResultadoCotejoDTO validarIa(@PathVariable String idExpediente) {
    return validacionIaService.validarExpediente(idExpediente);
  }

  @GetMapping("/{idExpediente}/ocr-resultados")
  public List<DocumentoOcrResultadoDTO> ocrResultados(@PathVariable String idExpediente) {
    return cargaDocumentoService.listarOcrResultados(idExpediente);
  }
}
