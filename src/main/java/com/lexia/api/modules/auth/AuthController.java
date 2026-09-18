package com.lexia.api.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf() {
    return Map.of("status", "OK");
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
}
