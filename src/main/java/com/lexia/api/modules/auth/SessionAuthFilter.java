package com.lexia.api.modules.auth;

import com.lexia.api.modules.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class SessionAuthFilter extends OncePerRequestFilter {

  private final UserSessionRepository sessions;

  public SessionAuthFilter(UserSessionRepository sessions) {
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String raw = CookieReader.get(request, AuthCookies.SESSION);
      if (raw != null && !raw.isBlank()) {
        try {
          UUID sessionId = UUID.fromString(raw);
          sessions
              .findById(sessionId)
              .filter(UserSession::isActive)
              .ifPresent(this::authenticate);
        } catch (IllegalArgumentException ignored) {
          // Cookie malformada: se trata como anónimo.
        }
      }
      filterChain.doFilter(request, response);
    } finally {
      AuthContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private void authenticate(UserSession session) {
    AuthPrincipal principal =
        new AuthPrincipal(
            session.getUserId(), session.getId(), session.getTenantId(), session.getMembershipId());
    AuthContext.set(principal);
    if (session.getTenantId() != null) {
      TenantContext.setTenantId(session.getTenantId());
    }
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
