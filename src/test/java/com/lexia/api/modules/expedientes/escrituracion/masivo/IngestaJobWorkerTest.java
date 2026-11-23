package com.lexia.api.modules.expedientes.escrituracion.masivo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.escrituracion.masivo.JobRepository.JobRow;
import com.lexia.api.modules.expedientes.escrituracion.masivo.JobRepository.JobsColgados;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestaJobWorkerTest {

  private static final String WORKER = "worker-test";
  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID LOTE = UUID.fromString("c1000000-0000-7000-8000-000000000001");

  private JobRepository jobs;
  private IngestaMasivaEscrituracionService ingesta;
  private IngestaJobWorker worker;

  @BeforeEach
  void setUp() {
    jobs = mock(JobRepository.class);
    ingesta = mock(IngestaMasivaEscrituracionService.class);
    when(ingesta.workerId()).thenReturn(WORKER);
    // El executor no corre nada: cada job lanzado retiene su hilo, como uno en curso.
    ExecutorService executor = mock(ExecutorService.class);
    worker = new IngestaJobWorker(jobs, ingesta, new ObjectMapper(), executor, 300, 360, 3);
  }

  @Test
  void pollReclamaHastaLlenarLosHilos() {
    when(jobs.reclamar(eq("PROCESAR_DOCUMENTO"), eq(WORKER), anyLong()))
        .thenAnswer(inv -> Optional.of(job(1)));

    worker.poll();
    worker.poll();

    verify(jobs, times(IngestaWorkerExecutorConfig.HILOS))
        .reclamar(eq("PROCESAR_DOCUMENTO"), eq(WORKER), anyLong());
  }

  @Test
  void pollSinColaSoltaLaCapacidad() {
    when(jobs.reclamar(any(), eq(WORKER), anyLong())).thenReturn(Optional.empty());

    worker.poll();
    worker.poll();

    verify(jobs, times(2)).reclamar(eq("PROCESAR_DOCUMENTO"), eq(WORKER), anyLong());
    verify(jobs, times(2)).reclamar(eq("PROCESAR_IA_EXPEDIENTE"), eq(WORKER), anyLong());
  }

  @Test
  void lockPorTipoSuperaElTimeout() {
    when(jobs.reclamar(any(), eq(WORKER), anyLong())).thenReturn(Optional.empty());

    worker.poll();

    verify(jobs).reclamar("PROCESAR_DOCUMENTO", WORKER, 300 + IngestaJobWorker.MARGEN_LOCK_SEGUNDOS);
    verify(jobs).reclamar("PROCESAR_IA_EXPEDIENTE", WORKER, 360 + IngestaJobWorker.MARGEN_LOCK_SEGUNDOS);
  }

  @Test
  void colgadoAgotadoMarcaSuItemEnError() {
    when(jobs.liberarJobsColgados(WORKER, 3)).thenReturn(new JobsColgados(2, List.of(job(3))));

    worker.liberarColgados();

    verify(ingesta).marcarItemComoError(eq(LOTE), eq("fila-1"), any());
  }

  @Test
  void colgadosReencoladosNoTocanItems() {
    when(jobs.liberarJobsColgados(WORKER, 3)).thenReturn(new JobsColgados(4, List.of()));

    worker.liberarColgados();

    verify(ingesta, never()).marcarItemComoError(any(), any(), any());
  }

  @Test
  void backoffCreceYTieneTope() {
    assertEquals(15, JobRepository.backoffSegundos(1));
    assertEquals(30, JobRepository.backoffSegundos(2));
    assertEquals(60, JobRepository.backoffSegundos(3));
    assertEquals(240, JobRepository.backoffSegundos(5));
    assertEquals(300, JobRepository.backoffSegundos(6));
    assertEquals(300, JobRepository.backoffSegundos(50));
    assertEquals(15, JobRepository.backoffSegundos(0));
  }

  private static JobRow job(int intentos) {
    String payload =
        "{\"loteId\":\"" + LOTE + "\",\"itemId\":\"fila-1\",\"tenantId\":\"" + TENANT + "\"}";
    return new JobRow(UUID.randomUUID(), TENANT, payload, intentos);
  }
}
