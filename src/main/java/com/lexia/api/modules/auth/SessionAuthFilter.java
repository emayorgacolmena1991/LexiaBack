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
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class SessionAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final UserSessionRepository sessions;
  private final SessionCache cache;

  public SessionAuthFilter(UserSessionRepository sessions, SessionCache cache) {
    this.sessions = sessions;
    this.cache = cache;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      UserSession session = resolveActiveSession(request);
      if (session != null) {
        authenticate(session);
      }
      filterChain.doFilter(request, response);
    } finally {
      AuthContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private UserSession resolveActiveSession(HttpServletRequest request) {
    UserSession fromCookie = lookupActive(CookieReader.get(request, AuthCookies.SESSION));
    if (fromCookie != null) {
      return fromCookie;
    }
    return lookupActive(bearerToken(request));
  }

  private UserSession lookupActive(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String token = raw.trim();
    UserSession porToken =
        cache.obtener(TokenHasher.sha256(token), hash -> sessions.findByTokenHash(hash).orElse(null));
    if (porToken != null) {
      return porToken;
    }
    return legado(token);
  }

  /**
   * Sesiones anteriores a V111: la cookie era el id. Solo valen si no tienen token_hash (no se
   * crean más así) y caducan en session-hours; el próximo refresh las migra a token.
   */
  private UserSession legado(String token) {
    UUID sessionId;
    try {
      sessionId = UUID.fromString(token);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
    return cache.obtener(
        "legado:" + sessionId,
        clave ->
            sessions
                .findById(sessionId)
                .filter(session -> session.getTokenHash() == null)
                .orElse(null));
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || header.length() < BEARER_PREFIX.length()) {
      return null;
    }
    if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
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
            principal,
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
