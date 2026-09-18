package com.lexia.api.modules.admin;

import jakarta.validation.constraints.Email;
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
      UUID id, String email, String displayName, Instant expiresAt, String status) {}
}
