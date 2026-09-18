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
      boolean mfaEnabled) {}

  public record MfaEnrollResponse(String secret, String otpauthUrl, String issuer) {}

  public record MfaStatusResponse(boolean enabled, String factorType, List<String> recoveryCodes) {}

  public record SessionResponse(
      UUID userId,
      String email,
      String displayName,
      UUID tenantId,
      String tenantName,
      List<String> roles,
      boolean mfaEnabled) {}
}
