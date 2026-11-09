package com.lexia.api.modules.notary;

import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
  private final DocxPdfConverter pdf;

  public NotaryController(
      NotaryCrossTenantService notary,
      NotaryLogoService logos,
      NotaryEscrituraService escrituras,
      DocxPdfConverter pdf) {
    this.notary = notary;
    this.logos = logos;
    this.escrituras = escrituras;
    this.pdf = pdf;
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

  /** Actualizar previsualización: re-render PDF con los valores del panel, sin guardar. */
  @PostMapping(
      value = "/remisiones/{id}/escritura/preview",
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> previewEscrituraConValores(
      @PathVariable UUID id, @RequestBody(required = false) NotaryDtos.GenerarEscrituraBody body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"escritura-preview.pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(escrituras.previewConValores(id, body));
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

  /** Vista previa PDF de una escritura ya generada (mismo .docx de descarga). */
  @GetMapping(
      value = "/remisiones/{id}/escrituras/{escrituraId}/preview",
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> previewEscritura(
      @PathVariable UUID id, @PathVariable UUID escrituraId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"escritura-preview.pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(escrituras.previewPdf(id, escrituraId));
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

  @GetMapping("/remisiones/{id}/versiones/{numero}/minuta/preview")
  public ResponseEntity<byte[]> previewMinuta(@PathVariable UUID id, @PathVariable int numero) {
    return pdfPreview(notary.leerMinutaVersion(id, numero), "minuta-v" + numero + ".pdf");
  }

  @GetMapping("/remisiones/{id}/versiones/{numero}/contrato")
  public ResponseEntity<byte[]> versionContrato(@PathVariable UUID id, @PathVariable int numero) {
    return archivo(notary.leerContratoVersion(id, numero));
  }

  @GetMapping("/remisiones/{id}/versiones/{numero}/contrato/preview")
  public ResponseEntity<byte[]> previewContrato(@PathVariable UUID id, @PathVariable int numero) {
    return pdfPreview(notary.leerContratoVersion(id, numero), "contrato-v" + numero + ".pdf");
  }

  @GetMapping(value = "/remisiones/{id}/versiones/{numero}/payload", produces = MediaType.APPLICATION_JSON_VALUE)
  public String versionPayload(@PathVariable UUID id, @PathVariable int numero) {
    return notary.leerPayloadVersion(id, numero);
  }

<<<<<<< HEAD
  private ResponseEntity<byte[]> pdfPreview(
      NotaryDtos.RemisionDocumentContent content, String fileName) {
    byte[] bytes = pdf.toPdf(content.bytes());
    ContentDisposition disposition =
        ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(bytes);
  }
=======
  private static final MediaType DOCX =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
>>>>>>> 28a3b99 (Chore: endspoints)

  private static ResponseEntity<byte[]> archivo(NotaryDtos.RemisionDocumentContent content) {
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(content.mimeType());
    } catch (Exception ex) {
      mediaType = MediaType.APPLICATION_OCTET_STREAM;
    }
    String nombre = content.fileName() == null || content.fileName().isBlank()
        ? "archivo"
        : content.fileName();
    ContentDisposition disposition =
        (esDocx(nombre, mediaType) ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(nombre, StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(mediaType)
        .body(content.bytes());
  }

<<<<<<< HEAD
=======
  private static boolean esDocx(String fileName, MediaType mediaType) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".docx") || DOCX.isCompatibleWith(mediaType);
  }
>>>>>>> 28a3b99 (Chore: endspoints)

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
