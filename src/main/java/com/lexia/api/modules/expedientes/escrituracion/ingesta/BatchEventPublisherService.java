package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Emite el estado del lote a los {@code EventSource} abiertos. */
@Service
public class BatchEventPublisherService {

  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emisores =
      new ConcurrentHashMap<>();

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
