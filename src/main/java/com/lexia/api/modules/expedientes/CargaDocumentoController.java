package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.CargaDocumentoDtos.ActualizarTipoRequest;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.BorradorResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.CrearBorradorRequest;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.IniciarProcesamientoResponse;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoActualizadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.TipoPermitidoDTO;
import com.lexia.api.modules.expedientes.PrevalidacionDtos.PrevalidacionDto;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * Paso 2: carga y tipificación. Contrato en {@code flujo.md}. OCR/Gemini queda en {@code
 * /procesar-documentos} para pantallas posteriores.
 */
@RestController
@RequestMapping("/api/v1/expedientes")
public class CargaDocumentoController {

  private final CargaDocumentoService cargaDocumentoService;
  private final PrevalidacionService prevalidacionService;

  public CargaDocumentoController(
      CargaDocumentoService cargaDocumentoService, PrevalidacionService prevalidacionService) {
    this.cargaDocumentoService = cargaDocumentoService;
    this.prevalidacionService = prevalidacionService;
  }

  @PostMapping("/borrador")
  @ResponseStatus(HttpStatus.CREATED)
  public BorradorResponse crearBorrador(@Valid @RequestBody CrearBorradorRequest request) {
    return cargaDocumentoService.crearBorrador(request.idActo());
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

  @GetMapping("/{idExpediente}/prevalidacion")
  public PrevalidacionDto prevalidacion(@PathVariable String idExpediente) {
    return prevalidacionService.obtener(idExpediente);
  }
}
