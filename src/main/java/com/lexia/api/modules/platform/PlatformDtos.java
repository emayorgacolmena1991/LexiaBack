package com.lexia.api.modules.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlatformDtos {

  private PlatformDtos() {}

  public record TenantSummary(
      UUID id,
      String code,
      String name,
      String tenantKind,
      String status,
      String planCode,
      Instant createdAt) {}

  public record TenantDetail(
      UUID id,
      String code,
      String name,
      String tenantKind,
      String status,
      String timezone,
      String planCode,
      Integer maxUsers,
      Integer maxCases,
      Instant createdAt,
      Instant updatedAt) {}

  public record UpdateTenantRequest(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String timezone,
      @Size(max = 64) String planCode,
      Integer maxUsers,
      Integer maxCases) {}

  public record UpdateTenantStatusRequest(
      @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED") String status) {}

  public record InviteAdminRequest(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(max = 160) String displayName) {}

  public record InviteAdminResponse(
      boolean adminActivated,
      UUID invitationId,
      Instant invitationExpiresAt) {}

  public record E2eInviteAdminResponse(
      boolean adminActivated,
      UUID invitationId,
      Instant invitationExpiresAt,
      String inviteToken,
      String inviteAcceptUrl) {}

  public record ProvisionTenantRequest(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}[a-z0-9]") String code,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "LEGAL_FIRM|NOTARY") String tenantKind,
      @Size(max = 64) String timezone,
      @Size(max = 64) String planCode,
      Integer maxUsers,
      Integer maxCases,
      List<@NotBlank @Size(max = 64) String> modules,
      @Valid LegalEntityInput legalEntity,
      @Valid NotaryProfileInput notaryProfile,
      @Valid PrimaryAdminInput primaryAdmin) {}

  public record LegalEntityInput(
      @Size(max = 32) String nit,
      @NotBlank @Size(max = 240) String legalName,
      @Size(max = 240) String tradeName,
      @Size(max = 32) String entityType,
      @Size(max = 320) String domicile) {}

  public record NotaryProfileInput(
      @NotBlank @Size(max = 20) String ruc,
      @NotBlank @Size(max = 120) String canton,
      @Size(max = 32) String notaryNumber,
      @NotBlank @Size(max = 240) String officialName,
      @Size(max = 200) String notaryHolderName,
      @Email @Size(max = 320) String contactEmail,
      Boolean listed) {}

  public record PrimaryAdminInput(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(max = 160) String displayName) {}

  public record ProvisionTenantResponse(
      UUID tenantId,
      String code,
      String name,
      String tenantKind,
      boolean primaryAdminActivated,
      UUID primaryAdminInvitationId,
      Instant invitationExpiresAt) {}

  public record E2eProvisionTenantResponse(
      UUID tenantId,
      String code,
      String name,
      String tenantKind,
      boolean primaryAdminActivated,
      UUID primaryAdminInvitationId,
      Instant invitationExpiresAt,
      String inviteToken,
      String inviteAcceptUrl) {}
}
