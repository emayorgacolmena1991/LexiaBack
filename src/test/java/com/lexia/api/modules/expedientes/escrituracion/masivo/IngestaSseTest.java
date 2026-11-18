package com.lexia.api.modules.expedientes.escrituracion.masivo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

class IngestaSseTest {

  private IngestaMasivaEscrituracionService service;
  private Map<UUID, CopyOnWriteArrayList<SseEmitter>> streams;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service =
        new IngestaMasivaEscrituracionService(
            null, null, null, null, null, null, null, null, null, null, null,
            mock(PlatformTransactionManager.class));
    streams = (Map<UUID, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(service, "streams");
  }

  @Test
  void latidoQuitaClientesCaidosYLaEntradaVacia() throws IOException {
    UUID lote = UUID.randomUUID();
    SseEmitter caido = mock(SseEmitter.class);
    doThrow(new IOException("broken pipe")).when(caido).send(any(SseEventBuilder.class));
    streams.put(lote, new CopyOnWriteArrayList<>(java.util.List.of(caido)));

    service.latidoSse();

    verify(caido).complete();
    assertEquals(0, service.lotesConOyentes());
  }

  @Test
  void latidoMantieneClientesVivos() throws IOException {
    UUID lote = UUID.randomUUID();
    SseEmitter vivo = mock(SseEmitter.class);
    SseEmitter caido = mock(SseEmitter.class);
    doThrow(new IllegalStateException("completado")).when(caido).send(any(SseEventBuilder.class));
    streams.put(lote, new CopyOnWriteArrayList<>(java.util.List.of(vivo, caido)));

    service.latidoSse();

    verify(vivo).send(any(SseEventBuilder.class));
    verify(vivo, never()).complete();
    assertEquals(1, streams.get(lote).size());
  }
}
