package com.lexia.api.modules.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {

  public static final String SESSION = "LEXIA_SID";
  public static final String REFRESH = "LEXIA_RT";

  private final AuthProperties properties;

  public AuthCookies(AuthProperties properties) {
    this.properties = properties;
  }

  public void write(HttpServletResponse response, String sessionId, String refreshToken) {
    add(response, session(sessionId, Duration.ofHours(properties.getSessionHours())));
    add(response, refresh(refreshToken, Duration.ofDays(properties.getRefreshDays())));
  }

  public void clear(HttpServletResponse response) {
    add(response, session("", Duration.ZERO));
    add(response, refresh("", Duration.ZERO));
  }

  private ResponseCookie session(String value, Duration maxAge) {
    return base(SESSION, value, maxAge).build();
  }

  private ResponseCookie refresh(String value, Duration maxAge) {
    return base(REFRESH, value, maxAge).path("/api/v1/auth").build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String name, String value, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge);
  }

  private static void add(HttpServletResponse response, ResponseCookie cookie) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
