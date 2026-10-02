package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ArchivoItem;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaDetalle;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaHeaderRequest;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaRequest;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaResumen;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.CargaMasivaResponse;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ConfirmacionResponse;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.GenerarExpedientesRequest;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.VincularRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/coactivas")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaIngestaController {

  private final ActaImportService actas;
  private final CargaMasivaService cargaMasiva;

  public CoactivaIngestaController(ActaImportService actas, CargaMasivaService cargaMasiva) {
    this.actas = actas;
    this.cargaMasiva = cargaMasiva;
  }

  @GetMapping("/actas")
  public List<ActaResumen> listarActas() {
    return actas.listar();
  }

  @PostMapping(value = "/actas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ActaDetalle importarActa(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String tipo,
      @RequestParam(required = false) String oficinaCodigo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaActa,
      @RequestParam(required = false) String titulo) {
    return actas.importar(file, tipo, oficinaCodigo, fechaActa, titulo);
  }

  @PostMapping(value = "/actas/manual", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ActaDetalle crearActaManual(@Valid @RequestBody ActaRequest request) {
    return actas.crearManual(request);
  }

  @GetMapping({"/actas/{id}", "/actas/{id}/borrador"})
  public ActaDetalle detalleActa(@PathVariable UUID id) {
    return actas.detalle(id);
  }

  @PatchMapping("/actas/{id}")
  public ActaDetalle actualizarActa(@PathVariable UUID id, @Valid @RequestBody ActaRequest request) {
    return actas.actualizar(id, request);
  }

  @PatchMapping("/actas/{id}/header")
  public ActaDetalle actualizarHeader(@PathVariable UUID id, @Valid @RequestBody ActaHeaderRequest request) {
    return actas.actualizarHeader(id, request.delegadoId());
  }

  @PostMapping("/actas/{id}/generar-expedientes")
  public ConfirmacionResponse generarExpedientes(
      @PathVariable UUID id, @Valid @RequestBody GenerarExpedientesRequest request) {
    return actas.generar(id, request.itemIds());
  }

  @PostMapping("/actas/{id}/confirmar")
  public ConfirmacionResponse confirmarActa(@PathVariable UUID id) {
    return actas.confirmar(id);
  }

  @PostMapping(value = "/carga-masiva", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CargaMasivaResponse cargaMasiva(@RequestPart("files") List<MultipartFile> files) {
    return cargaMasiva.subir(files);
  }

  @GetMapping("/carga-masiva/sin-asignar")
  public List<ArchivoItem> sinAsignar() {
    return cargaMasiva.sinAsignar();
  }

  @PostMapping("/carga-masiva/{archivoId}/vincular")
  public ArchivoItem vincular(@PathVariable UUID archivoId, @Valid @RequestBody VincularRequest request) {
    return cargaMasiva.vincular(archivoId, request.expedienteId());
  }

  @DeleteMapping("/carga-masiva/{archivoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void descartar(@PathVariable UUID archivoId) {
    cargaMasiva.descartar(archivoId);
  }
}
