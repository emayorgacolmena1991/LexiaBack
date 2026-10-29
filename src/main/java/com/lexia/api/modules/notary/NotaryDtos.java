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
      String notariaNombre,
      Instant emitidoEn,
      Instant recibidoEn,
      String observacionesBufete,
      String observacionesNotaria,
      List<RemisionDocumentDto> documentos) {}

  public record AdvanceRemisionBody(String observacionesNotaria) {}

  public record DevolverRemisionBody(String observacionesNotaria) {}
}
