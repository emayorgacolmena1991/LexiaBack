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
                    .ignoringRequestMatchers(
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/login"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/mfa/verify"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/refresh")))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        paths.matcher("/api/v1/health"),
                        paths.matcher("/actuator/health"),
                        paths.matcher("/actuator/info"),
                        paths.matcher("/api/v1/auth/login"),
                        paths.matcher("/api/v1/auth/mfa/verify"),
                        paths.matcher("/api/v1/auth/refresh"),
                        paths.matcher("/api/v1/auth/csrf"))
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(SecurityConfig::unauthorized)
                    .accessDeniedHandler(SecurityConfig::unauthorized))
        .build();
  }

  private static void unauthorized(
      HttpServletRequest request, HttpServletResponse response, Exception exception)
      throws java.io.IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Sesión no válida o vencida.\"}");
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
