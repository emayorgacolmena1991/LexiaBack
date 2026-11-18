package com.lexia.api.modules.expedientes.coactivas.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Emite SSE al navegador de esta instancia. El evento también se publica con
 * {@code pg_notify} para que otra instancia, con el navegador conectado, lo entregue.
 */
@Service
public class BatchEventPublisherService {

  private static final Logger LOG = LoggerFactory.getLogger(BatchEventPublisherService.class);
  private static final String NODE = UUID.randomUUID().toString();
  private static final String CANAL = "lexia_sse";

  private final JdbcTemplate jdbc;
  private final String url;
  private final String usuario;
  private final String clave;
  private final ObjectMapper json;
  private final AtomicBoolean vivo = new AtomicBoolean(true);
  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emisores =
      new ConcurrentHashMap<>();

  /** LISTEN ocupa su conexión de por vida: va directa al driver para no restarla del pool. */
  public BatchEventPublisherService(
      JdbcTemplate jdbc,
      @Value("${spring.datasource.url:}") String url,
      @Value("${spring.datasource.username:}") String usuario,
      @Value("${spring.datasource.password:}") String clave,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.url = url;
    this.usuario = usuario;
    this.clave = clave;
    this.json = json;
    if (url == null || url.isBlank()) {
      LOG.warn("Sin spring.datasource.url: los SSE no se reciben de otros nodos");
      return;
    }
    Thread hilo = new Thread(this::escuchar, "sse-listen");
    hilo.setDaemon(true);
    hilo.start();
  }

  @PreDestroy
  void cerrar() {
    vivo.set(false);
  }

  public SseEmitter abrir(UUID batchId) {
    // 15 min: Azure puede usar sus 5 y detrás van consolidar e IA. 5 min cortaba el stream a mitad.
    SseEmitter emitter = new SseEmitter(15L * 60L * 1000L);
    emisores.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    Runnable quitar = () -> quitarEmisor(batchId, emitter);
    emitter.onCompletion(() -> {
      LOG.info("SSE completado {}", batchId);
      quitar.run();
    });
    emitter.onTimeout(() -> {
      LOG.warn("SSE timeout {}", batchId);
      quitar.run();
    });
    emitter.onError(error -> quitar.run());
    return emitter;
  }

  /** Contrato del cuerpo: {@code {"evento": "...", "data": {...}, "ts": "ISO8601"}}. */
  public static Map<String, Object> sobre(String evento, Object data) {
    Map<String, Object> cuerpo = new LinkedHashMap<>();
    cuerpo.put("evento", evento);
    cuerpo.put("data", data);
    cuerpo.put("ts", Instant.now().toString());
    return cuerpo;
  }

  public void publicar(UUID batchId, String evento, Object data) {
    Map<String, Object> cuerpo = sobre(evento, data);
    emitirLocal(batchId, evento, cuerpo);
    try {
      UUID eventoId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO app.sse_evento (id, expediente_id, tipo, cuerpo) VALUES (?, ?, ?, ?)",
          eventoId,
          batchId,
          evento,
          json.writeValueAsString(cuerpo));
      jdbc.query(
          "SELECT pg_notify(?, ?)",
          rs -> {},
          CANAL,
          NODE + "|" + batchId + "|" + evento + "|" + eventoId);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
      LOG.warn("No se publicó el SSE al resto de nodos: {}", ex.getMessage());
    }
  }

  /** El listener ignora el aviso que originó este mismo proceso. */
  static boolean esPropio(String aviso, String node) {
    return aviso != null && aviso.startsWith(node + "|");
  }

  private void escuchar() {
    while (vivo.get()) {
      try (Connection connection = DriverManager.getConnection(url, usuario, clave);
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(true);
        statement.execute("LISTEN " + CANAL);
        PGConnection pg = connection.unwrap(PGConnection.class);
        while (vivo.get()) {
          PGNotification[] notas = pg.getNotifications(1000);
          if (notas == null) {
            continue;
          }
          for (PGNotification nota : notas) {
            entregarRemoto(nota.getParameter());
          }
        }
      } catch (RuntimeException | java.sql.SQLException ex) {
        if (vivo.get()) {
          LOG.warn("Canal SSE desconectado: {}", ex.getMessage());
          try {
            Thread.sleep(2000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }
  }

  private void entregarRemoto(String aviso) {
    if (esPropio(aviso, NODE)) {
      return;
    }
    String[] partes = aviso == null ? new String[0] : aviso.split("\\|", 4);
    if (partes.length < 4) {
      return;
    }
    try {
      UUID expedienteId = UUID.fromString(partes[1]);
      String evento = partes[2];
      UUID eventoId = UUID.fromString(partes[3]);
      String cuerpo =
          jdbc.query(
              "SELECT cuerpo FROM app.sse_evento WHERE id = ?",
              rs -> rs.next() ? rs.getString(1) : null,
              eventoId);
      if (cuerpo == null) {
        return;
      }
      emitirLocal(expedienteId, evento, json.readTree(cuerpo));
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
      LOG.warn("SSE remoto ilegible: {}", ex.getMessage());
    }
  }

  private void emitirLocal(UUID batchId, String evento, Object data) {
    List<SseEmitter> lista = emisores.get(batchId);
    if (lista == null) {
      return;
    }
    for (SseEmitter emitter : lista) {
      try {
        emitter.send(SseEmitter.event().name(evento).data(data));
      } catch (IOException | IllegalStateException ex) {
        emitter.complete();
        quitarEmisor(batchId, emitter);
      }
    }
  }

  /** Quita el emisor y, si la lista queda vacía, también la entrada del expediente. */
  private void quitarEmisor(UUID batchId, SseEmitter emitter) {
    emisores.computeIfPresent(
        batchId,
        (id, lista) -> {
          lista.remove(emitter);
          return lista.isEmpty() ? null : lista;
        });
  }

  public int expedientesConOyentes() {
    return emisores.size();
  }

  /**
   * app.sse_evento solo sirve para que otro nodo lea el cuerpo tras el pg_notify (milisegundos).
   * Sin purga crecía con cada evento.
   */
  @Scheduled(fixedDelayString = "${lexia.sse.purga-ms:600000}")
  public void purgarEventos() {
    int borrados =
        jdbc.update("DELETE FROM app.sse_evento WHERE created_at < now() - INTERVAL '1 hour'");
    if (borrados > 0) {
      LOG.info("sse_evento purgados={}", borrados);
    }
  }
}
