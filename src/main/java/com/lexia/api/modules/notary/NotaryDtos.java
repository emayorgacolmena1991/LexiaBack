package com.lexia.api.modules.notary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotaryDtos {

  private NotaryDtos() {}

  public record NotaryCatalogItemDto(
      UUID notaryTenantId,
      String tenantCode,
      String officialName,
      String canton,
      String ruc,
      String notaryNumber,
      String notaryHolderName,
      String contactEmail,
      boolean assigned,
      String partnershipStatus) {}

  public record PartnershipDto(
      UUID id,
      UUID firmTenantId,
      UUID notaryTenantId,
      String status,
      Instant approvedAt,
      String notaryOfficialName,
      String firmTenantName) {}

  public record RemisionDocumentContent(String fileName, String mimeType, byte[] bytes) {}

  public record RequestPartnershipBody(String notaryTenantCode, String notaryRuc) {}

  public record SetAssignmentBody(boolean active) {}

  public record CreateRemisionBody(
      UUID notaryTenantId,
      UUID legalCaseId,
      String observacionesBufete) {}

  public record RemisionDocumentDto(
      UUID id, String displayName, Long sizeBytes, String statusLabel, int sortOrder) {}

  public record RemisionDto(
      UUID id,
      UUID firmTenantId,
      UUID notaryTenantId,
      UUID firmLegalCaseId,
      String expedienteCodigo,
      String asunto,
      String estado,
      int pasoNotarial,
      String actaLabel,
      int documentosCount,
      int versionActual,
      String notariaNombre,
      Instant emitidoEn,
      Instant recibidoEn,
      Instant actualizadoEn,
      String observacionesBufete,
      String observacionesNotaria,
      List<RemisionDocumentDto> documentos) {}

  public record CrearVersionBody(String observacionesBufete) {}

  public record RemisionVersionDto(
      int versionNo,
      Instant createdAt,
      String createdBy,
      String observacionesBufete,
      String minutaFileName,
      String contratoFileName,
      long cantidadDocumentos) {}

  public record RemisionVersionDetalleDto(
      int versionNo,
      Instant createdAt,
      String createdBy,
      String observacionesBufete,
      String minutaFileName,
      String contratoFileName,
      List<RemisionDocumentDto> documentos) {}

  public record AdvanceRemisionBody(String observacionesNotaria) {}

  public record DevolverRemisionBody(String observacionesNotaria) {}

  public record CampoFormularioDto(String clave, String etiqueta, String valor, String origen) {}

  public record EscrituraFormularioDto(int versionNo, List<CampoFormularioDto> campos) {}

  public record GenerarEscrituraBody(java.util.Map<String, String> valores) {}

  public record EscrituraDto(
      UUID id,
      int versionNo,
      String fileName,
      Instant generatedAt,
      String generatedBy,
      List<String> camposVacios,
      java.util.Map<String, String> valores) {}
}
