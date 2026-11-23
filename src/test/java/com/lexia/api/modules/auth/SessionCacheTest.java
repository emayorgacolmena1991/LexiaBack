package com.lexia.api.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionCacheTest {

  private final RelojMovil reloj = new RelojMovil();
  private final AtomicInteger cargas = new AtomicInteger();
  private SessionCache cache;
  private UserSession sesion;
  private Function<String, UserSession> cargar;

  @BeforeEach
  void setUp() {
    cache = new SessionCache(Duration.ofSeconds(30), reloj);
    sesion =
        UserSession.open(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ip", "ua",
            Instant.now().plus(Duration.ofHours(8)));
    cargar =
        id -> {
          cargas.incrementAndGet();
          return sesion;
        };
  }

  @Test
  void dentroDelTtlNoVuelveALaBd() {
    assertSame(sesion, cache.obtener(clave(), cargar));
    reloj.avanzar(Duration.ofSeconds(29));
    assertSame(sesion, cache.obtener(clave(), cargar));
    assertEquals(1, cargas.get());
  }

  @Test
  void vencidoElTtlRecarga() {
    cache.obtener(clave(), cargar);
    reloj.avanzar(Duration.ofSeconds(31));
    cache.obtener(clave(), cargar);
    assertEquals(2, cargas.get());
  }

  @Test
  void revocarLaSesionLaSacaDeLaCache() {
    cache.obtener(clave(), cargar);
    sesion.revoke();
    new UserSessionCacheListener().invalidar(sesion);

    assertNull(cache.obtener(clave(), cargar));
    assertEquals(2, cargas.get());
  }

  @Test
  void sesionRevocadaEnLaMismaInstanciaNoSeDevuelveAunqueSigaEnCache() {
    cache.obtener(clave(), cargar);
    sesion.revoke();

    assertNull(cache.obtener(clave(), id -> null));
  }

  @Test
  void sesionInexistenteNoSeCachea() {
    assertNull(cache.obtener("otra", id -> null));
    assertEquals(0, cache.tamano());
  }

  @Test
  void rotarElTokenInvalidaLaEntradaDelTokenAnterior() {
    String anterior = clave();
    cache.obtener(anterior, cargar);
    sesion.emitirToken();
    new UserSessionCacheListener().invalidar(sesion);

    assertNull(cache.obtener(anterior, h -> null));
    assertEquals(0, cache.tamano());
  }

  @Test
  void elHashGuardadoNoEsElToken() {
    String token = sesion.emitirToken();
    assertEquals(TokenHasher.sha256(token), sesion.getTokenHash());
    org.junit.jupiter.api.Assertions.assertNotEquals(token, sesion.getTokenHash());
  }

  /** Clave de caché = hash del token vigente de la sesión. */
  private String clave() {
    if (sesion.getTokenHash() == null) {
      sesion.emitirToken();
    }
    return sesion.getTokenHash();
  }

  @Test
  void ttlCeroDesactivaLaCache() {
    SessionCache sinCache = new SessionCache(Duration.ZERO, reloj);
    sinCache.obtener(clave(), cargar);
    sinCache.obtener(clave(), cargar);
    assertEquals(2, cargas.get());
  }

  private static final class RelojMovil extends Clock {
    private Instant ahora = Instant.parse("2026-11-17T12:00:00Z");

    void avanzar(Duration d) {
      ahora = ahora.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return ahora;
    }
  }
}
