package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaEventoRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipanteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaTareaSugeridaRepository;
import com.lexia.api.modules.expedientes.coactivas.jobs.BatchEventPublisherService;
import com.lexia.api.modules.expedientes.coactivas.jobs.CoactivaJobQueue;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * El job del PDF anterior puede terminar después del reemplazo y, como el vigente se elige por
 * {@code createdAt}, su save pisa el diagnóstico del PDF nuevo.
 */
@ExtendWith(MockitoExtension.class)
class CoactivaAnalisisReemplazoTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-0000000000a1");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-0000000000a1");

  @Mock private CoactivaArchivoRepository archivos;
  @Mock private CoactivaArchivoStorage storage;
  @Mock private CoactivaExpedienteRepository expedientes;
  @Mock private CoactivaParticipanteRepository participantes;
  @Mock private CoactivaAnalisisRepository analisisRepo;
  @Mock private CoactivaEventoRepository eventos;
  @Mock private AnalisisDocumentoService analisis;
  @Mock private CoactivaPromptCatalogRepository catalog;
  @Mock private CoactivaTareaSugeridaRepository tareas;
  @Mock private CoactivaJobQueue jobs;
  @Mock private BatchEventPublisherService sse;
  @Mock private CoactivaValidacionIaWorker validacion;

  private final List<CoactivaAnalisis> guardados = new ArrayList<>();
  private CoactivaExpedienteAnalisisWorker worker;
  private CoactivaExpediente expediente;

  @BeforeEach
  void setUp() {
    worker =
        new CoactivaExpedienteAnalisisWorker(
            archivos,
            storage,
            expedientes,
            participantes,
            analisisRepo,
            eventos,
            null,
            analisis,
            catalog,
            new CoactivaDiagnosticoParser(new ObjectMapper()),
            tareas,
            jobs,
            sse,
            validacion,
            tx(),
            true);
    expediente = CoactivaExpediente.create(TENANT, UUID.randomUUID(), "025-2024-00026", USER);
    when(archivos.findByIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return Optional.ofNullable(lookup(id));
            });
    when(expedientes.findByIdAndTenantIdAndDeletedAtIsNull(expediente.getId(), TENANT))
        .thenReturn(Optional.of(expediente));
    when(expedientes.bloquear(expediente.getId(), TENANT)).thenReturn(Optional.of(expediente));
    when(analisisRepo.save(any(CoactivaAnalisis.class)))
        .thenAnswer(
            inv -> {
              CoactivaAnalisis row = inv.getArgument(0);
              guardados.add(row);
              return row;
            });
    when(analisisRepo.findFirstByTenantIdAndExpedienteIdAndEstadoOrderByCreatedAtDesc(
            TENANT, expediente.getId(), CoactivaAnalisis.ANALIZADO))
        .thenAnswer(inv -> ultimo(CoactivaAnalisis.ANALIZADO));
  }

  @Test
  void jobDelPdfAnteriorNoPisaElAnalisisDelReemplazo() throws Exception {
    CoactivaArchivo pdfA = pdf("a.pdf");
    pdfA.iniciarAnalisisIa(null);
    expediente.marcarAnalizando(pdfA.getId());
    worker.encolar(TENANT, expediente.getId(), pdfA.getId());

    CoactivaArchivo pdfB = pdf("b.pdf");
    pdfB.vincularExpediente(expediente.getId());
    pdfB.iniciarAnalisisIa(null);
    expediente.marcarAnalizando(pdfB.getId());
    worker.encolar(TENANT, expediente.getId(), pdfB.getId());
    worker.ejecutar(TENANT, pdfB.getId());

    Thread.sleep(5);
    worker.ejecutar(TENANT, pdfA.getId());

    CoactivaAnalisis ultimo =
        guardados.stream()
            .filter(a -> CoactivaAnalisis.ANALIZADO.equals(a.getEstado()))
            .max(Comparator.comparing(CoactivaAnalisis::getCreatedAt))
            .orElseThrow();
    assertEquals(pdfB.getId(), ultimo.getArchivoId());
  }

  @Test
  void siATerminaAntesQueBElVigenteSigueSiendoB() throws Exception {
    CoactivaArchivo pdfA = pdf("a.pdf");
    pdfA.iniciarAnalisisIa(null);
    expediente.marcarAnalizando(pdfA.getId());
    worker.encolar(TENANT, expediente.getId(), pdfA.getId());

    CoactivaArchivo pdfB = pdf("b.pdf");
    pdfB.iniciarAnalisisIa(null);
    expediente.marcarAnalizando(pdfB.getId());
    worker.encolar(TENANT, expediente.getId(), pdfB.getId());

    worker.ejecutar(TENANT, pdfA.getId());
    Thread.sleep(5);
    worker.ejecutar(TENANT, pdfB.getId());

    CoactivaAnalisis ultimo =
        guardados.stream()
            .filter(a -> CoactivaAnalisis.ANALIZADO.equals(a.getEstado()))
            .max(Comparator.comparing(CoactivaAnalisis::getCreatedAt))
            .orElseThrow();
    assertEquals(pdfB.getId(), ultimo.getArchivoId());
  }

  private final List<CoactivaArchivo> pdfs = new ArrayList<>();

  private CoactivaArchivo pdf(String nombre) {
    CoactivaArchivo archivo =
        CoactivaArchivo.create(
            UUID.randomUUID(),
            TENANT,
            CoactivaArchivo.EXPEDIENTE_UNIFICADO,
            nombre,
            "application/pdf",
            10,
            nombre,
            "data/" + nombre,
            USER);
    archivo.vincularExpediente(expediente.getId());
    pdfs.add(archivo);
    return archivo;
  }

  private CoactivaArchivo lookup(UUID id) {
    for (CoactivaArchivo archivo : pdfs) {
      if (archivo.getId().equals(id)) {
        return archivo;
      }
    }
    return null;
  }

  private Optional<CoactivaAnalisis> ultimo(String estado) {
    return guardados.stream()
        .filter(a -> estado.equals(a.getEstado()))
        .max(Comparator.comparing(CoactivaAnalisis::getCreatedAt));
  }

  private static PlatformTransactionManager tx() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
