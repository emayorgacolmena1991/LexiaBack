package com.lexia.api.modules.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Lee X-Tenant-Id. Tras el login, SessionAuthFilter pisa el valor con el tenant de la sesión. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantHeaderFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Tenant-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String raw = request.getHeader(HEADER);
      if (raw != null && !raw.isBlank()) {
        try {
          TenantContext.setTenantId(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ignored) {
          // Header malformado: SessionAuthFilter puede fijar tenant desde la sesión.
        }
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
