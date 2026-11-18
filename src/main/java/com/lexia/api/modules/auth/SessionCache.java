package com.lexia.api.modules.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sesiones activas en memoria para no consultar app.user_session en cada request. La clave es la
 * del token de la cookie (su hash), nunca el token en claro. La revocación o rotación en este nodo
 * se aplica al instante ({@link UserSessionCacheListener}); en otro nodo tarda como máximo el TTL.
 * La expiración por tiempo se revisa en cada lectura. TTL 0 desactiva la caché.
 */
@Component
public class SessionCache {

  static final int MAX_ENTRADAS = 10_000;

  /** Lo usa el listener JPA, que Hibernate instancia fuera de Spring. */
  private static volatile SessionCache instancia;

  private final Duration ttl;
  private final Clock clock;
  private final ConcurrentHashMap<String, Entrada> entradas = new ConcurrentHashMap<>();

  @Autowired
  public SessionCache(@Value("${lexia.auth.session-cache-seconds:30}") long ttlSegundos) {
    this(Duration.ofSeconds(Math.max(0, ttlSegundos)), Clock.systemUTC());
  }

  SessionCache(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
    instancia = this;
  }

  /** Sesión activa desde caché o, si no está o venció su TTL, desde {@code cargar}. */
  public UserSession obtener(String clave, Function<String, UserSession> cargar) {
    Instant ahora = clock.instant();
    Entrada entrada = entradas.get(clave);
    if (entrada != null && entrada.vence().isAfter(ahora)) {
      if (entrada.sesion().isActive()) {
        return entrada.sesion();
      }
      entradas.remove(clave, entrada);
      return null;
    }
    UserSession cargada = cargar.apply(clave);
    if (cargada == null || !cargada.isActive()) {
      entradas.remove(clave);
      return null;
    }
    if (!ttl.isZero()) {
      if (entradas.size() >= MAX_ENTRADAS) {
        purgar(ahora);
      }
      entradas.put(clave, new Entrada(cargada, ahora.plus(ttl)));
    }
    return cargada;
  }

  /**
   * Quita todas las entradas de la sesión (incluida la de un token ya rotado) y, si hay
   * transacción, otra vez tras el commit para no recachear el valor viejo.
   */
  public void invalidar(UUID sessionId) {
    if (sessionId == null) {
      return;
    }
    quitar(sessionId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              quitar(sessionId);
            }
          });
    }
  }

  static void invalidarGlobal(UUID sessionId) {
    SessionCache cache = instancia;
    if (cache != null) {
      cache.invalidar(sessionId);
    }
  }

  int tamano() {
    return entradas.size();
  }

  /** Recorre como mucho {@link #MAX_ENTRADAS}; solo en revocaciones y rotaciones (poco frecuentes). */
  private void quitar(UUID sessionId) {
    entradas.values().removeIf(e -> sessionId.equals(e.sesion().getId()));
  }

  private void purgar(Instant ahora) {
    entradas.entrySet().removeIf(e -> !e.getValue().vence().isAfter(ahora));
    if (entradas.size() >= MAX_ENTRADAS) {
      entradas.clear();
    }
  }

  private record Entrada(UserSession sesion, Instant vence) {}
}
