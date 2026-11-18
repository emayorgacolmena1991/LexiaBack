package com.lexia.api.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** S7: los fallos desde una IP frenan a esa IP para la cuenta, no bloquean al usuario real. */
@ExtendWith(MockitoExtension.class)
class AuthLoginThrottleTest {

  private static final String EMAIL = "laura.gomez@lexia.demo";

  @Mock private AuthProperties properties;
  @Mock private AppUserRepository users;
  @Mock private LoginAttemptRepository attempts;
  @Mock private AuditEventRepository auditEvents;
  @Mock private PasswordHasher passwords;
  @Mock private E2eProperties e2eProperties;

  @InjectMocks private AuthService service;

  @BeforeEach
  void setUp() {
    lenient().when(properties.getMaxFailedAttempts()).thenReturn(5);
    lenient().when(properties.getLockoutMinutes()).thenReturn(30);
    lenient().when(attempts.countByIpAddressAndCreatedAtAfterAndSuccessFalse(any(), any())).thenReturn(0L);
  }

  @Test
  void cincoFallosSeguidosDesdeLaIpDevuelven429SinTocarLaCuenta() {
    historial(fallos(5));

    AuthException error = assertThrows(AuthException.class, this::login);

    assertEquals("RATE_LIMITED", error.getCode());
    verify(users, never()).findByEmail(any());
  }

  @Test
  void contrasenaBuenaConMfaPendienteNoReiniciaLaRacha() {
    List<LoginAttempt> lista = new ArrayList<>(fallos(2));
    lista.add(LoginAttempt.record(EMAIL, null, true, "MFA_REQUIRED"));
    lista.addAll(fallos(3));
    historial(lista);

    AuthException error = assertThrows(AuthException.class, this::login);

    assertEquals("RATE_LIMITED", error.getCode());
  }

  @Test
  void unLoginCorrectoCierraLaRacha() {
    List<LoginAttempt> lista = new ArrayList<>(fallos(4));
    lista.add(LoginAttempt.record(EMAIL, null, true, "PASSWORD"));
    lista.addAll(fallos(10));
    historial(lista);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

    AuthException error = assertThrows(AuthException.class, this::login);

    assertNotEquals("RATE_LIMITED", error.getCode());
  }

  private void historial(List<LoginAttempt> masRecienteprimero) {
    when(attempts.findTop20ByEmailAndIpAddressOrderByCreatedAtDesc(eq(EMAIL), any()))
        .thenReturn(masRecienteprimero);
  }

  private static List<LoginAttempt> fallos(int n) {
    List<LoginAttempt> lista = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      lista.add(LoginAttempt.record(EMAIL, null, false, "BAD_PASSWORD"));
    }
    return lista;
  }

  private void login() {
    service.login(
        new AuthDtos.LoginRequest(EMAIL, "x", null),
        mock(HttpServletRequest.class),
        mock(HttpServletResponse.class));
  }
}
