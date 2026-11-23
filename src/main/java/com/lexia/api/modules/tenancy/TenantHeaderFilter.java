package com.lexia.api.modules.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limpia TenantContext al terminar cada request. Con autenticación activa el tenant sale solo de la
 * sesión (SessionAuthFilter): X-Tenant-Id lo manda el cliente y, en rutas sin sesión, fijaba el
 * tenant de RLS a voluntad. El header solo se usa con lexia.auth.enabled=false (desarrollo).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantHeaderFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Tenant-Id";

  private final boolean authEnabled;

  public TenantHeaderFilter(@Value("${lexia.auth.enabled:true}") boolean authEnabled) {
    this.authEnabled = authEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      if (!authEnabled) {
        aplicarHeader(request);
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private static void aplicarHeader(HttpServletRequest request) {
    String raw = request.getHeader(HEADER);
    if (raw == null || raw.isBlank()) {
      return;
    }
    try {
      TenantContext.setTenantId(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ignored) {
      // Header malformado: se ignora, el request sigue sin tenant.
    }
  }
}
