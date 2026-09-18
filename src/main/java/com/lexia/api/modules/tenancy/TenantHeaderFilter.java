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

/** Cimiento: lee X-Tenant-Id. El JWT productivo sustituirá este header. */
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
        TenantContext.setTenantId(UUID.fromString(raw.trim()));
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
