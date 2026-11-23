package com.lexia.api.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** S5: CSRF en toda mutación con cookie de sesión; no en Bearer sin cookie ni en GET. */
class SecurityConfigCsrfTest {

  @Test
  void mutacionConCookieDeSesionExigeCsrf() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/escrituracion/ingesta-masiva/batch");
    req.setCookies(new Cookie("LEXIA_SID", "abc"));
    assertTrue(SecurityConfig.requiereCsrf(req));
  }

  @Test
  void bearerConCookieTambienExigeCsrf() {
    MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/v1/coactivas/expedientes/1");
    req.setCookies(new Cookie("LEXIA_SID", "abc"));
    req.addHeader("Authorization", "Bearer abc");
    assertTrue(SecurityConfig.requiereCsrf(req));
  }

  @Test
  void bearerSinCookieNoExigeCsrf() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ocr/azure/analyze-batch");
    req.addHeader("Authorization", "Bearer abc");
    assertFalse(SecurityConfig.requiereCsrf(req));
  }

  @Test
  void lecturasNoExigenCsrf() {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/coactivas/expedientes");
    req.setCookies(new Cookie("LEXIA_SID", "abc"));
    assertFalse(SecurityConfig.requiereCsrf(req));
  }
}
