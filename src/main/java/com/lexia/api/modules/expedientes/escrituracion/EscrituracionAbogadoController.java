package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.BorradorGeneradoResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ConfigurarProductoRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ContenidoMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.DatosBiessMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoEscrituracion;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoMinutaBorrador;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.GuardarContenidoMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.GuardarMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaGuardadaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.PreviewMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.VariablesMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.RegularizacionResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ProductoItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.WritingSnapshot;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.expedientes.escrituracion.ProcesarExpedienteCompletoResult;
import com.lexia.api.modules.expedientes.minutas.CapturaBiessService;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class EscrituracionAbogadoController {

  private final ProductoBiessService productos;
  private final EscrituracionAbogadoService escritura;
  private final IaAnalysisService iaAnalysis;
  private final MinutaGenerationService minutaGeneration;
  private final CapturaBiessService capturaBiess;
  private final ExpedienteEstadoService estado;
  private final RegularizacionEscrituracionService regularizacion;

  public EscrituracionAbogadoController(
      ProductoBiessService productos,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          EscrituracionAbogadoService escritura,
      IaAnalysisService iaAnalysis,
      MinutaGenerationService minutaGeneration,
      CapturaBiessService capturaBiess,
      ExpedienteEstadoService estado,
      RegularizacionEscrituracionService regularizacion) {
    this.productos = productos;
    this.escritura = escritura;
    this.iaAnalysis = iaAnalysis;
    this.minutaGeneration = minutaGeneration;
    this.capturaBiess = capturaBiess;
    this.estado = estado;
    this.regularizacion = regularizacion;
  }

  @GetMapping("/productos-biess")
  public List<ProductoItem> listarProductos() {
    return productos.listarProductos();
  }

  @GetMapping("/productos-biess/{code}")
  public ProductoDetalle detalleProducto(
      @PathVariable String code, @RequestParam(required = false) String canton) {
    return productos.detalle(code, canton);
  }

  /** Índice de etapa y paso del wizard para retomar el flujo. Acepta UUID o código LEX-…. */
  @GetMapping("/expedientes/{id}/escrituracion/estado")
  public EstadoEscrituracion estado(@PathVariable String id) {
    return estado.estadoFlujo(id);
  }

  @PostMapping({
    "/expedientes/{id}/escrituracion/enviar-regularizacion",
    "/escrituracion/expedientes/{id}/enviar-regularizacion"
  })
  public RegularizacionResponse enviarRegularizacion(@PathVariable UUID id) {
    return regularizacion.enviar(id);
  }

  @PostMapping("/expedientes/{id}/escrituracion/marcar-corregido")
  public RegularizacionResponse marcarCorregido(@PathVariable UUID id) {
    return regularizacion.marcarCorregido(id);
  }

  @PostMapping({
    "/expedientes/{id}/escrituracion/marcar-regularizado",
    "/escrituracion/expedientes/{id}/marcar-regularizado"
  })
  public RegularizacionResponse marcarRegularizado(@PathVariable UUID id) {
    return regularizacion.marcarRegularizado(id);
  }

  @PostMapping("/expedientes/{id}/escrituracion/observaciones/{observacionId}/resolver")
  public RegularizacionResponse resolverObservacion(
      @PathVariable UUID id, @PathVariable UUID observacionId) {
    return regularizacion.resolverObservacion(id, observacionId);
  }

  @PatchMapping({
    "/expedientes/{id}/escrituracion/observaciones/{observacionId}/cerrar",
    "/escrituracion/expedientes/{id}/observaciones/{observacionId}/cerrar"
  })
  public RegularizacionResponse cerrarObservacion(
      @PathVariable UUID id, @PathVariable UUID observacionId) {
    return regularizacion.resolverObservacion(id, observacionId);
  }

  @GetMapping({
    "/expedientes/{id}/escrituracion",
    "/escrituracion/expedientes/{id}"
  })
  public ResponseEntity<WritingSnapshot> snapshot(@PathVariable UUID id) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.snapshot(id));
  }

  @PutMapping("/expedientes/{id}/escrituracion/producto")
  public ResponseEntity<WritingSnapshot> configurarProducto(
      @PathVariable UUID id, @Valid @RequestBody ConfigurarProductoRequest request) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.configurarProducto(id, request));
  }

  @PostMapping("/expedientes/{id}/escrituracion/estudio-titulo")
  public ResponseEntity<EstudioTituloResponse> estudioTitulo(
      @PathVariable UUID id, @Valid @RequestBody EstudioTituloRequest request) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.registrarEstudio(id, request));
  }

  @PostMapping("/expedientes/{id}/escrituracion/analizar-ia")
  public ResponseEntity<ProcesarExpedienteCompletoResult> analizarIa(
      @PathVariable UUID id, @RequestBody(required = false) AnalysisRequestDTO request) {
    return ResponseEntity.ok(iaAnalysis.analizarExpedienteConPromptProducto(id, request));
  }

  /** Mismo single-pass que analizar-ia. Sin forceReanalysis devuelve lo persistido en BD. */
  @PostMapping("/expedientes/{id}/procesar-completo")
  public ResponseEntity<ProcesarExpedienteCompletoResult> procesarCompleto(
      @PathVariable UUID id, @RequestBody(required = false) AnalysisRequestDTO request) {
    return ResponseEntity.ok(iaAnalysis.analizarExpedienteConPromptProducto(id, request));
  }

  @PostMapping("/expedientes/{id}/escrituracion/minutas")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<MinutaItem> crearMinuta(
      @PathVariable UUID id, @Valid @RequestBody(required = false) CrearMinutaRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            minutaGeneration.generar(
                id, request == null ? new CrearMinutaRequest(null, null) : request));
  }

  /**
   * TICKET-BE-503 [B]: genera el borrador (sustitución de tags en la plantilla .docx, sin LLM
   * sobre el texto legal) y devuelve el contenido listo para el editor + URL de descarga.
   */
  @PostMapping("/expedientes/{id}/minutas/{tipoMinuta}/generar-borrador")
  public ResponseEntity<BorradorGeneradoResponse> generarBorrador(
      @PathVariable UUID id,
      @PathVariable String tipoMinuta,
      @RequestBody(required = false) CrearMinutaRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(minutaGeneration.generarBorrador(id, tipoMinuta, request));
  }

  /**
   * TICKET-INT-102 [C.1]: JSON unificado (LLM + BIESS + overrides) con las variables pendientes
   * del acto. Solo lectura.
   */
  @GetMapping("/expedientes/{id}/minutas/{tipoMinuta}/variables")
  public VariablesMinutaResponse variablesMinuta(
      @PathVariable UUID id, @PathVariable String tipoMinuta) {
    return minutaGeneration.variables(id, tipoMinuta);
  }

  /**
   * TICKET-INT-102 [C.2]: aplica las variables del panel, re-renderiza el .docx con poi-tl y
   * devuelve el PDF de vista previa. El .docx descargable queda idéntico a lo previsualizado.
   */
  @PostMapping(
      value = "/expedientes/{id}/minutas/{tipoMinuta}/preview",
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> previewMinuta(
      @PathVariable UUID id,
      @PathVariable String tipoMinuta,
      @RequestBody(required = false) PreviewMinutaRequest request) {
    MinutaGenerationService.PreviewMinuta preview =
        minutaGeneration.previsualizar(id, tipoMinuta, request);
    return ResponseEntity.ok()
        .header("X-Minuta-Id", preview.minutaId().toString())
        .header(
            "X-Variables-Pendientes",
            String.valueOf(preview.variables().variablesPendientes().size()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"minuta-preview.pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(preview.pdf());
  }

  @GetMapping("/expedientes/{id}/escrituracion/minutas/{minutaId}/download")
  public ResponseEntity<byte[]> descargarMinuta(
      @PathVariable UUID id, @PathVariable UUID minutaId) {
    return docx(minutaGeneration.descargar(id, minutaId));
  }

  /** TICKET-BE-503 [C]: descarga por id de minuta; sirve el binario guardado (generado o editado). */
  @GetMapping("/minutas/{minutaId}/download")
  public ResponseEntity<byte[]> descargarMinutaPorId(@PathVariable UUID minutaId) {
    return docx(minutaGeneration.descargarPorMinuta(minutaId));
  }

  /** TICKET-BE-503 [C]: "Guardar" del editor (texto + datos BIESS) regenerando el binario. */
  @PutMapping("/minutas/{minutaId}")
  public MinutaGuardadaResponse guardarMinutaPorId(
      @PathVariable UUID minutaId, @RequestBody(required = false) GuardarMinutaRequest request) {
    return minutaGeneration.guardarPorMinuta(minutaId, request);
  }

  @PutMapping("/expedientes/{id}/escrituracion/minuta-borrador/{minutaId}")
  public MinutaGuardadaResponse guardarMinuta(
      @PathVariable UUID id,
      @PathVariable UUID minutaId,
      @RequestBody(required = false) GuardarMinutaRequest request) {
    return minutaGeneration.guardar(id, minutaId, request);
  }

  private static ResponseEntity<byte[]> docx(MinutaGenerationService.DownloadedMinuta file) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .body(file.bytes());
  }

  /**
   * Estado persistido (BIESS, cotejo, minuta) para hidratar el FE sin re-ejecutar OCR ni LLM.
   */
  @GetMapping("/expedientes/{id}/escrituracion/minuta-borrador")
  public EstadoMinutaBorrador minutaBorrador(@PathVariable UUID id) {
    return estado.hidratar(id);
  }

  /**
   * TICKET-BE-503 [A]: captura BIESS (JPG/PNG/PDF) → monto, tasa, plazo, cuota y apoderado;
   * se persisten en extracted_data (grupo biess) del expediente.
   */
  @PostMapping(
      value = {
        "/expedientes/{id}/escrituracion/captura-biess",
        "/expedientes/{id}/extract-biess-screenshot"
      },
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public DatosBiessMinuta extraerCapturaBiess(
      @PathVariable UUID id, @RequestParam("file") MultipartFile file) {
    return capturaBiess.extraer(id, file);
  }

  @GetMapping("/expedientes/{id}/escrituracion/minuta-borrador/{minutaId}/datos-biess")
  public DatosBiessMinutaResponse obtenerDatosBiess(
      @PathVariable UUID id, @PathVariable UUID minutaId) {
    return minutaGeneration.obtenerDatosBiess(id, minutaId);
  }

  @PutMapping("/expedientes/{id}/escrituracion/minuta-borrador/{minutaId}/datos-biess")
  public DatosBiessMinutaResponse aplicarDatosBiess(
      @PathVariable UUID id,
      @PathVariable UUID minutaId,
      @RequestBody DatosBiessMinuta request) {
    return minutaGeneration.aplicarDatosBiess(id, minutaId, request);
  }

  /** Párrafos del DOCX guardado de la minuta (mismo archivo que la descarga). */
  @GetMapping("/expedientes/{id}/escrituracion/minuta-borrador/{minutaId}/contenido")
  public ContenidoMinutaResponse obtenerContenidoMinuta(
      @PathVariable UUID id, @PathVariable UUID minutaId) {
    return minutaGeneration.obtenerContenido(id, minutaId);
  }

  /** Guarda el texto editado por párrafo sobre el mismo DOCX. */
  @PutMapping("/expedientes/{id}/escrituracion/minuta-borrador/{minutaId}/contenido")
  public ContenidoMinutaResponse guardarContenidoMinuta(
      @PathVariable UUID id,
      @PathVariable UUID minutaId,
      @RequestBody GuardarContenidoMinutaRequest request) {
    return minutaGeneration.guardarContenido(id, minutaId, request);
  }
}
