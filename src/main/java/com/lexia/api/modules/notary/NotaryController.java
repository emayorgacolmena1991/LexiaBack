package com.lexia.api.modules.notary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/notaria")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryController {

  private final NotaryCrossTenantService notary;
  private final NotaryLogoService logos;
  private final NotaryEscrituraService escrituras;

  public NotaryController(
      NotaryCrossTenantService notary, NotaryLogoService logos, NotaryEscrituraService escrituras) {
    this.notary = notary;
    this.logos = logos;
    this.escrituras = escrituras;
  }

  @GetMapping("/remisiones/{id}/escritura/formulario")
  public NotaryDtos.EscrituraFormularioDto formularioEscritura(@PathVariable UUID id) {
    return escrituras.formulario(id);
  }

  @PostMapping("/remisiones/{id}/escritura")
  public NotaryDtos.EscrituraDto generarEscritura(
      @PathVariable UUID id, @RequestBody(required = false) NotaryDtos.GenerarEscrituraBody body) {
    return escrituras.generar(id, body);
  }

  @GetMapping("/remisiones/{id}/escrituras")
  public List<NotaryDtos.EscrituraDto> historialEscrituras(@PathVariable UUID id) {
    return escrituras.historial(id);
  }

  @GetMapping("/remisiones/{id}/escrituras/{escrituraId}/archivo")
  public ResponseEntity<byte[]> archivoEscritura(
      @PathVariable UUID id, @PathVariable UUID escrituraId) {
    return archivo(escrituras.archivo(id, escrituraId));
  }

  @PutMapping(value = "/perfil/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void subirLogo(@RequestParam("file") MultipartFile file) {
    logos.subir(file);
  }

  @DeleteMapping("/perfil/logo")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void quitarLogo() {
    logos.quitar();
  }

  @GetMapping("/perfil/logo")
  public ResponseEntity<byte[]> leerLogo() {
    return archivo(logos.leer());
  }

  @GetMapping("/catalogo")
  public List<NotaryDtos.NotaryCatalogItemDto> catalogo() {
    return notary.catalogForFirm();
  }

  @PostMapping("/partnerships")
  public NotaryDtos.PartnershipDto requestPartnership(@RequestBody NotaryDtos.RequestPartnershipBody body) {
    return notary.requestPartnership(body);
  }

  @GetMapping("/partnerships/pendientes")
  public List<NotaryDtos.PartnershipDto> partnershipsPendientes() {
    return notary.pendingPartnershipsForNotary();
  }

  @PostMapping("/partnerships/{id}/aprobar")
  public NotaryDtos.PartnershipDto approvePartnership(@PathVariable UUID id) {
    return notary.approvePartnership(id);
  }

  @PutMapping("/asignaciones/{notaryTenantId}")
  public void setAssignment(@PathVariable UUID notaryTenantId, @RequestBody NotaryDtos.SetAssignmentBody body) {
    notary.setAssignment(notaryTenantId, body.active());
  }

  @PostMapping("/asignaciones/limpiar")
  public void clearAssignments() {
    notary.clearAssignments();
  }

  @GetMapping("/remisiones")
  public List<NotaryDtos.RemisionDto> bandeja() {
    return notary.bandejaNotaria();
  }

  @GetMapping("/remisiones/caso/{legalCaseId}")
  public NotaryDtos.RemisionDto remisionPorCaso(@PathVariable UUID legalCaseId) {
    return notary
        .ultimaRemisionForCase(legalCaseId)
        .orElseThrow(() -> NotaryBusinessException.notFound("Sin remisión para el expediente."));
  }

  @GetMapping("/remisiones/{id}")
  public NotaryDtos.RemisionDto getRemision(@PathVariable UUID id) {
    return notary.getRemision(id);
  }

  @GetMapping("/remisiones/{remisionId}/documentos/{documentId}/contenido")
  public ResponseEntity<byte[]> remisionDocumentoContenido(
      @PathVariable UUID remisionId, @PathVariable UUID documentId) {
    return archivo(notary.readRemisionDocument(remisionId, documentId));
  }

  @PostMapping("/remisiones/{id}/versiones")
  public NotaryDtos.RemisionDto crearVersion(
      @PathVariable UUID id, @RequestBody(required = false) NotaryDtos.CrearVersionBody body) {
    return notary.editarRemision(id, body == null ? new NotaryDtos.CrearVersionBody(null) : body);
  }

  @GetMapping("/remisiones/{id}/versiones")
  public List<NotaryDtos.RemisionVersionDto> versiones(@PathVariable UUID id) {
    return notary.listarVersiones(id);
  }

  @GetMapping("/remisiones/{id}/versiones/{numero}")
  public NotaryDtos.RemisionVersionDetalleDto version(
      @PathVariable UUID id, @PathVariable int numero) {
    return notary.detalleVersion(id, numero);
  }

  @GetMapping("/remisiones/{id}/versiones/{numero}/minuta")
  public ResponseEntity<byte[]> versionMinuta(@PathVariable UUID id, @PathVariable int numero) {
    return archivo(notary.leerMinutaVersion(id, numero));
  }

  @GetMapping("/remisiones/{id}/versiones/{numero}/contrato")
  public ResponseEntity<byte[]> versionContrato(@PathVariable UUID id, @PathVariable int numero) {
    return archivo(notary.leerContratoVersion(id, numero));
  }

  @GetMapping(value = "/remisiones/{id}/versiones/{numero}/payload", produces = MediaType.APPLICATION_JSON_VALUE)
  public String versionPayload(@PathVariable UUID id, @PathVariable int numero) {
    return notary.leerPayloadVersion(id, numero);
  }

  private static ResponseEntity<byte[]> archivo(NotaryDtos.RemisionDocumentContent content) {
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(content.mimeType());
    } catch (Exception ex) {
      mediaType = MediaType.APPLICATION_OCTET_STREAM;
    }
    ContentDisposition disposition =
        ContentDisposition.inline()
            .filename(content.fileName(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentType(mediaType)
        .body(content.bytes());
  }

  @PostMapping("/remisiones/{id}/recibir")
  public NotaryDtos.RemisionDto recibir(@PathVariable UUID id) {
    return notary.getRemisionAndAcknowledge(id);
  }

  @PostMapping("/remisiones")
  public NotaryDtos.RemisionDto crear(@RequestBody NotaryDtos.CreateRemisionBody body) {
    return notary.createRemision(body);
  }

  @PostMapping("/remisiones/{id}/avanzar")
  public NotaryDtos.RemisionDto avanzar(@PathVariable UUID id) {
    return notary.advanceRemision(id);
  }

  @PostMapping("/remisiones/{id}/devolver")
  public NotaryDtos.RemisionDto devolver(
      @PathVariable UUID id, @RequestBody(required = false) NotaryDtos.DevolverRemisionBody body) {
    return notary.devolverRemision(id, body == null ? new NotaryDtos.DevolverRemisionBody(null) : body);
  }
}
