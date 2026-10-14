package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
  private final DataSource dataSource;
  private final ObjectMapper json;
  private final AtomicBoolean vivo = new AtomicBoolean(true);
  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emisores =
      new ConcurrentHashMap<>();

  public BatchEventPublisherService(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.json = json;
    Thread hilo = new Thread(this::escuchar, "sse-listen");
    hilo.setDaemon(true);
    hilo.start();
  }

  @PreDestroy
  void cerrar() {
    vivo.set(false);
  }

  public SseEmitter abrir(UUID batchId) {
    SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
    emisores.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    Runnable quitar = () -> {
      List<SseEmitter> lista = emisores.get(batchId);
      if (lista != null) {
        lista.remove(emitter);
      }
    };
    emitter.onCompletion(quitar);
    emitter.onTimeout(quitar);
    emitter.onError(error -> quitar.run());
    return emitter;
  }

  public void publicar(UUID batchId, String evento, Object data) {
    emitirLocal(batchId, evento, data);
    try {
      UUID eventoId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO app.sse_evento (id, expediente_id, tipo, cuerpo) VALUES (?, ?, ?, ?)",
          eventoId,
          batchId,
          evento,
          json.writeValueAsString(data));
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
      try (Connection connection = dataSource.getConnection();
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
        lista.remove(emitter);
      }
    }
  }
}
