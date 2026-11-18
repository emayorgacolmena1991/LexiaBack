package com.lexia.api.config;

import com.lexia.api.modules.auth.AuthProperties;
import com.lexia.api.modules.auth.SessionAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  // Sin MODE_INHERITABLETHREADLOCAL: un hilo de pool creado durante un request heredaba la sesión
  // de ese usuario. Los executors propagan el contexto explícitamente (AsyncConfig).

  @Bean
  @ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
  FilterRegistrationBean<SessionAuthFilter> sessionAuthRegistration(SessionAuthFilter filter) {
    FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException(username);
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, AuthProperties properties, ObjectProvider<SessionAuthFilter> sessionFilter)
      throws Exception {
    http.cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults())
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

    http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable);

    if (!properties.isEnabled()) {
      return http.csrf(AbstractHttpConfigurer::disable)
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .build();
    }

    PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookieCustomizer(
        cookie -> cookie.sameSite("Lax").secure(properties.isCookieSecure()).path("/"));
    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

    SessionAuthFilter sessionAuthFilter = sessionFilter.getIfAvailable();
    if (sessionAuthFilter != null) {
      http.addFilterAfter(sessionAuthFilter, SecurityContextHolderFilter.class);
    }

    return http.csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(requestHandler)
                    .requireCsrfProtectionMatcher(SecurityConfig::requiereCsrf)
                    // Solo endpoints previos a tener sesión (o que la cierran). Las mutaciones de la
                    // app van autenticadas con cookie: sin CSRF, otra web del mismo sitio podía
                    // dispararlas con la sesión del usuario.
                    .ignoringRequestMatchers(
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/login"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/logout"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/e2e/session"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/mfa/verify"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/select-tenant"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/invite/accept"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/password/forgot"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/password/reset")))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // P0: métricas internas (pool, colas, workers) solo desde la propia máquina.
                    .requestMatchers(SecurityConfig::metricasDesdeLoopback)
                    .permitAll()
                    .requestMatchers(
                        paths.matcher("/actuator/metrics"), paths.matcher("/actuator/metrics/**"))
                    .denyAll()
                    .requestMatchers(
                        paths.matcher("/error"),
                        paths.matcher("/api/v1/health"),
                        paths.matcher("/actuator/health"),
                        paths.matcher("/actuator/info"),
                        paths.matcher("/api/v1/auth/login"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/logout"),
                        paths.matcher("/api/v1/auth/e2e/session"),
                        paths.matcher("/api/v1/auth/mfa/verify"),
                        paths.matcher("/api/v1/auth/select-tenant"),
                        paths.matcher("/api/v1/auth/tenant-selection/*/options"),
                        paths.matcher("/api/v1/auth/refresh"),
                        paths.matcher("/api/v1/auth/csrf"),
                        paths.matcher("/api/v1/auth/invite/accept"),
                        paths.matcher("/api/v1/auth/password/forgot"),
                        paths.matcher("/api/v1/auth/password/reset"),
                        paths.matcher("/api/v1/escrituracion/ingesta-masiva/*/stream"),
                        paths.matcher(HttpMethod.GET, "/api/v1/coactivas/expedientes/{id}/stream"))
                    .permitAll()
                    // TICKET-DEV-401B: /api/v1/cache/** (E03 consolidate + E04 cotejo) requiere sesión.
                    .requestMatchers(
                        paths.matcher("/api/v1/actos-notariales"),
                        paths.matcher("/api/v1/actos-notariales/**"),
                        paths.matcher("/api/v1/productos-biess"),
                        paths.matcher("/api/v1/productos-biess/**"),
                        paths.matcher("/api/v1/expedientes"),
                        paths.matcher("/api/v1/expedientes/**"),
                        paths.matcher("/api/v1/minutas/**"),
                        paths.matcher("/api/v1/escrituracion"),
                        paths.matcher("/api/v1/escrituracion/**"),
                        paths.matcher("/api/v1/notaria"),
                        paths.matcher("/api/v1/notaria/**"),
                        paths.matcher("/api/v1/ia/**"),
                        paths.matcher("/api/v1/ocr/**"),
                        paths.matcher("/api/v1/documents/**"),
                        paths.matcher("/api/v1/cache/**"),
                        paths.matcher("/api/v1/cache/cotejo/**"))
                    .hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "USER")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(SecurityConfig::unauthorized)
                    .accessDeniedHandler(SecurityConfig::forbidden))
        .build();
  }

  /**
   * GET a /actuator/metrics desde loopback. remoteAddr no viene de X-Forwarded-For salvo que
   * Tomcat confíe en el proxy (forward-headers-strategy); detrás de uno, el cliente externo no es
   * loopback y recibe 403.
   */
  static boolean metricasDesdeLoopback(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (!"GET".equals(request.getMethod())
        || uri == null
        || !(uri.equals("/actuator/metrics") || uri.startsWith("/actuator/metrics/"))) {
      return false;
    }
    String ip = request.getRemoteAddr();
    return "127.0.0.1".equals(ip)
        || "0:0:0:0:0:0:0:1".equals(ip)
        || "::1".equals(ip);
  }

  private static final java.util.Set<String> METODOS_SEGUROS =
      java.util.Set.of("GET", "HEAD", "TRACE", "OPTIONS");

  /**
   * CSRF protege credenciales que el navegador manda solo (la cookie de sesión). Una petición con
   * Bearer y sin cookie de sesión (cliente API, pruebas) no la necesita: el token no viaja solo.
   */
  static boolean requiereCsrf(HttpServletRequest request) {
    if (METODOS_SEGUROS.contains(request.getMethod())) {
      return false;
    }
    String autorizacion = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
    boolean bearer =
        autorizacion != null && autorizacion.regionMatches(true, 0, "Bearer ", 0, 7);
    String sid =
        com.lexia.api.modules.auth.CookieReader.get(
            request, com.lexia.api.modules.auth.AuthCookies.SESSION);
    boolean cookieSesion = sid != null && !sid.isBlank();
    return !(bearer && !cookieSesion);
  }

  private static void unauthorized(
      HttpServletRequest request, HttpServletResponse response, Exception exception)
      throws java.io.IOException {
    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Sesión no válida o vencida.");
  }

  private static void forbidden(
      HttpServletRequest request, HttpServletResponse response, Exception exception)
      throws java.io.IOException {
    writeJson(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "No autorizado para esta acción.");
  }

  private static void writeJson(HttpServletResponse response, int status, String code, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }

  static final class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        jakarta.servlet.FilterChain filterChain)
        throws jakarta.servlet.ServletException, java.io.IOException {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        token.getToken();
      }
      filterChain.doFilter(request, response);
    }
  }
}
