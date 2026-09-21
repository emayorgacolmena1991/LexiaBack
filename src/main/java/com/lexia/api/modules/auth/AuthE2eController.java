package com.lexia.api.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/e2e")
@ConditionalOnProperty(name = "lexia.e2e.enabled", havingValue = "true")
public class AuthE2eController {

  private final AuthService authService;

  public AuthE2eController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/session")
  public AuthDtos.AuthResponse session(
      @Valid @RequestBody AuthDtos.LoginRequest request,
      HttpServletRequest http,
      HttpServletResponse response) {
    return authService.e2eLogin(request, http, response);
  }
}
