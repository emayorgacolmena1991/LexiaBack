package com.lexia.api.modules.notary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notaria")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryController {

  private final NotaryCrossTenantService notary;

  public NotaryController(NotaryCrossTenantService notary) {
    this.notary = notary;
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
    NotaryDtos.RemisionDocumentContent content = notary.readRemisionDocument(remisionId, documentId);
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
