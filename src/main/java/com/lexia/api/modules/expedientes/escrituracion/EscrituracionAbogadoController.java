package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ConfigurarProductoRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.DatosBiessMinutaResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoEscrituracion;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoMinutaBorrador;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
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

  public EscrituracionAbogadoController(
      ProductoBiessService productos,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          EscrituracionAbogadoService escritura,
      IaAnalysisService iaAnalysis,
      MinutaGenerationService minutaGeneration,
      CapturaBiessService capturaBiess,
      ExpedienteEstadoService estado) {
    this.productos = productos;
    this.escritura = escritura;
    this.iaAnalysis = iaAnalysis;
    this.minutaGeneration = minutaGeneration;
    this.capturaBiess = capturaBiess;
    this.estado = estado;
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

  @GetMapping("/expedientes/{id}/escrituracion")
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

  @GetMapping("/expedientes/{id}/escrituracion/minutas/{minutaId}/download")
  public ResponseEntity<byte[]> descargarMinuta(
      @PathVariable UUID id, @PathVariable UUID minutaId) {
    var file = minutaGeneration.descargar(id, minutaId);
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

  /** Captura BIESS: persiste monto, tasa, plazo y apoderado en extracted_data del expediente. */
  @PostMapping(
      value = "/expedientes/{id}/escrituracion/captura-biess",
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
}
