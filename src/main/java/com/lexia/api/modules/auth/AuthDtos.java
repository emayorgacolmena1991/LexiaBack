package com.lexia.api.modules.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {}

  public record LoginRequest(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(min = 8, max = 200) String password) {}

  public record MfaVerifyRequest(
      @NotBlank String challengeId, @NotBlank @Size(min = 6, max = 20) String code) {}

  public record MfaConfirmRequest(@NotBlank @Size(min = 6, max = 6) String code) {}

  public record AuthResponse(
      String status,
      String challengeId,
      UUID userId,
      String email,
      String displayName,
      UUID tenantId,
      String tenantName,
      List<String> roles,
      List<String> permissions,
      boolean mfaEnabled,
      /** Opaco para `Authorization: Bearer` (mismo id que cookie LEXIA_SID). */
      @com.fasterxml.jackson.annotation.JsonProperty("accessToken")
          @com.fasterxml.jackson.annotation.JsonAlias("token")
          String accessToken) {}

  public record MfaEnrollResponse(String secret, String otpauthUrl, String issuer) {}

  public record MfaStatusResponse(boolean enabled, String factorType, List<String> recoveryCodes) {}

  public record TenantModuleItem(String code, boolean enabled) {}

  public record SessionResponse(
      UUID userId,
      String email,
      String displayName,
      UUID tenantId,
      String tenantName,
      List<String> roles,
      List<String> permissions,
      List<TenantModuleItem> modules,
      boolean mfaEnabled,
      boolean notifyOnLogin,
      String locale,
      String timezone,
      String theme,
      String accessToken) {}

  public record ProfileUpdateRequest(@NotBlank @Size(max = 160) String displayName) {}

  public record UserPreferencesRequest(
      @NotBlank @Size(max = 10) String locale,
      @Size(max = 64) String timezone,
      @NotBlank @Size(max = 16) String theme) {}

  public record AcceptInviteRequest(
      @NotBlank String token, @NotBlank @Size(min = 8, max = 200) String password) {}

  public record ChangePasswordRequest(
      @NotBlank @Size(min = 8, max = 200) String currentPassword,
      @NotBlank @Size(min = 8, max = 200) String newPassword) {}

  public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 320) String email) {}

  public record ResetPasswordRequest(
      @NotBlank String token, @NotBlank @Size(min = 8, max = 200) String password) {}

  public record NotificationPreferencesRequest(boolean notifyOnLogin) {}
}
