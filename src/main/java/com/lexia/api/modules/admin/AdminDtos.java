package com.lexia.api.modules.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {

  private AdminDtos() {}

  public record InviteUserRequest(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(max = 160) String displayName,
      List<@NotBlank @Size(max = 64) String> roles) {}

  public record InviteUserResponse(UUID invitationId, String email, Instant expiresAt) {}

  public record RoleOption(String code, String name) {}

  public record InvitationItem(
      UUID id,
      String email,
      String displayName,
      String roleCode,
      String roleName,
      Instant createdAt,
      Instant expiresAt,
      String status) {}

  public record TenantMemberItem(
      UUID userId,
      UUID membershipId,
      String email,
      String displayName,
      String roleCode,
      String roleName,
      String membershipStatus,
      String userStatus,
      boolean mfaEnabled,
      Instant memberSince) {}

  public record UpdateMemberRoleRequest(@NotBlank @Size(max = 64) String roleCode) {}

  public record PageResponse<T>(List<T> items, long total, int page, int size) {}

  public record PermissionOption(String code, String name, String moduleCode) {}

  public record RoleDetail(
      UUID id,
      String code,
      String name,
      String description,
      boolean system,
      List<String> permissionCodes,
      long memberCount) {}

  public record CreateRoleRequest(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 160) String name,
      @Size(max = 200) String description,
      List<@NotBlank @Size(max = 96) String> permissionCodes) {}

  public record UpdateRoleRequest(
      @NotBlank @Size(max = 160) String name,
      @Size(max = 200) String description,
      List<@NotBlank @Size(max = 96) String> permissionCodes) {}

  public record TenantDetail(
      UUID id,
      String code,
      String name,
      String status,
      String timezone,
      String planCode,
      Integer maxUsers,
      Integer maxCases,
      long activeMembers,
      String caseCodePattern,
      int slaDefaultHours,
      Instant updatedAt) {}

  public record UpdateTenantRequest(
      @Size(max = 200) String name,
      @Size(max = 64) String timezone,
      Integer maxUsers,
      @Size(max = 64) String caseCodePattern,
      @Min(1) @Max(8760) Integer slaDefaultHours) {}

  public record TenantSecuritySettings(int inviteTtlHours, boolean mfaRequired) {}

  public record UpdateTenantSecurityRequest(Integer inviteTtlHours, Boolean mfaRequired) {}

  public record E2eInviteResponse(
      UUID invitationId, String email, String token, Instant expiresAt) {}

  public record CatalogSummary(
      UUID id, String code, String name, long itemCount, long activeItemCount) {}

  public record CatalogItemDetail(
      UUID id, String code, String label, int sortOrder, boolean active) {}

  public record CatalogDetail(UUID id, String code, String name, List<CatalogItemDetail> items) {}

  public record CreateCatalogRequest(
      @NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name) {}

  public record CreateCatalogItemRequest(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String label,
      int sortOrder) {}

  public record UpdateCatalogItemRequest(
      @NotBlank @Size(max = 200) String label, int sortOrder, boolean active) {}

  public record AuditEventItem(
      UUID id,
      Instant createdAt,
      String actorType,
      UUID actorUserId,
      String actorEmail,
      String actorDisplayName,
      String event,
      String objectType,
      UUID objectId,
      String result,
      String ipAddress) {}

  public record AuditSummary(long deniedLast24h) {}

  public record IntegrationItem(
      UUID id,
      String code,
      String name,
      boolean enabled,
      String lastCallStatus,
      String lastCallAtLabel,
      boolean toggleAllowed) {}

  public record EmailChannelStatus(
      boolean brevoEnabled,
      boolean apiKeyConfigured,
      String senderEmail,
      boolean integrationEnabled,
      String lastTestStatus,
      String lastTestAtLabel) {}

  public record IntegrationsOverview(
      List<IntegrationItem> connectors,
      EmailChannelStatus email,
      long enabledCount,
      long totalCount) {}

  public record UpdateIntegrationRequest(Boolean enabled) {}
}
