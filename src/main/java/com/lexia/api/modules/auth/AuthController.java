package com.lexia.api.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AuthController {

  private final AuthService authService;
  private final PasswordService passwordService;

  public AuthController(AuthService authService, PasswordService passwordService) {
    this.authService = authService;
    this.passwordService = passwordService;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken csrfToken) {
    if (csrfToken == null) {
      return Map.of("status", "OK");
    }
    return Map.of(
        "status", "OK",
        "token", csrfToken.getToken(),
        "headerName", csrfToken.getHeaderName());
  }

  @PostMapping("/login")
  public AuthDtos.AuthResponse login(
      @Valid @RequestBody AuthDtos.LoginRequest request,
      HttpServletRequest http,
      HttpServletResponse response) {
    return authService.login(request, http, response);
  }

  @PostMapping("/mfa/verify")
  public AuthDtos.AuthResponse verifyMfa(
      @Valid @RequestBody AuthDtos.MfaVerifyRequest request,
      HttpServletRequest http,
      HttpServletResponse response) {
    return authService.verifyMfa(request, http, response);
  }

  @PostMapping("/refresh")
  public AuthDtos.AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
    return authService.refresh(request, response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    authService.logout(request, response);
  }

  @GetMapping("/me")
  public AuthDtos.SessionResponse me() {
    return authService.me();
  }

  @GetMapping("/mfa")
  public AuthDtos.MfaStatusResponse mfaStatus() {
    return authService.mfaStatus();
  }

  @PostMapping("/mfa/enroll")
  public AuthDtos.MfaEnrollResponse enroll() {
    return authService.enrollMfa();
  }

  @PostMapping("/mfa/confirm")
  public AuthDtos.MfaStatusResponse confirm(@Valid @RequestBody AuthDtos.MfaConfirmRequest request) {
    return authService.confirmMfa(request);
  }

  @PostMapping("/mfa/disable")
  public AuthDtos.MfaStatusResponse disable(@Valid @RequestBody AuthDtos.MfaConfirmRequest request) {
    return authService.disableMfa(request);
  }

  @PostMapping("/invite/accept")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void acceptInvite(@Valid @RequestBody AuthDtos.AcceptInviteRequest request) {
    passwordService.acceptInvitation(request.token(), request.password());
  }

  @PostMapping("/password/change")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
    passwordService.changePassword(request.currentPassword(), request.newPassword());
  }

  @PostMapping("/password/forgot")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest request) {
    passwordService.requestReset(request.email());
  }

  @PostMapping("/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
    passwordService.resetPassword(request.token(), request.password());
  }

  @PostMapping("/notifications")
  public AuthDtos.NotificationPreferencesRequest updateNotifications(
      @Valid @RequestBody AuthDtos.NotificationPreferencesRequest request) {
    return authService.updateNotificationPreferences(request);
  }

  @PatchMapping("/profile")
  public AuthDtos.SessionResponse updateProfile(@Valid @RequestBody AuthDtos.ProfileUpdateRequest request) {
    return authService.updateProfile(request);
  }

  @PatchMapping("/preferences")
  public AuthDtos.UserPreferencesRequest updatePreferences(
      @Valid @RequestBody AuthDtos.UserPreferencesRequest request) {
    return authService.updatePreferences(request);
  }
}
