package com.lexia.api.modules.expedientes.escrituracion.ingesta;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Saca trabajos de {@code app.jobs}. Al arrancar, lo que quedó EN_PROCESO vuelve a la cola. */
@Component
public class IngestaJobWorker {

  private static final Logger LOG = LoggerFactory.getLogger(IngestaJobWorker.class);

  private final IngestaJobQueue cola;
  private final IngestaMasivaEscrituracionService ingesta;
  private final AtomicBoolean arranque = new AtomicBoolean(true);

  public IngestaJobWorker(IngestaJobQueue cola, IngestaMasivaEscrituracionService ingesta) {
    this.cola = cola;
    this.ingesta = ingesta;
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    if (arranque.compareAndSet(true, false)) {
      int reanudados = cola.reanudarInterrumpidos();
      if (reanudados > 0) {
        LOG.info("ingesta jobs reanudados={}", reanudados);
      }
    }
    Optional<IngestaJobQueue.Job> job = cola.reclamar();
    if (job.isEmpty()) {
      return;
    }
    IngestaJobQueue.Job actual = job.get();
    try {
      ingesta.ejecutarJob(actual);
      cola.completar(actual.id());
    } catch (RuntimeException ex) {
      LOG.warn("ingesta job {} falló: {}", actual.id(), ex.getMessage());
      cola.fallar(actual.id(), ex.getMessage());
    }
  }
}
